package com.github.shivawu.sbt.maven

import java.io.File
import xml._

import sbt._
import Keys._

import property.PomProperty
import property.ResolveUtil

class Pom private (val pomFile: File) { self =>
  require(pomFile.exists, "[" + pomFile.getCanonicalPath + "] doesn't exist")

  ConsoleLogger().info("Loading [" + pomFile + "]")
  val baseDir = pomFile.getCanonicalFile.getParent

  val xml = XML.loadFile(pomFile) \\ "project"

  // Object methods
  override def toString = "Pom(" + pomFile + ")"

  // Util methods
  private def getText(x: NodeSeq): Option[String] = 
    x.headOption.map(_.text)

  // Parent
  val parentCoordinates = 
    if ((xml \ "parent").isEmpty) (None, None, None)
    else {
      val pnt = (xml \ "parent") 
      (
        getText(pnt \ "groupId"), 
        getText(pnt \ "artifactId"), 
        getText(pnt \ "version")
      )
    }
  lazy val parent: Option[Pom] = 
    if ((xml \ "parent").isEmpty)
      None
    else {
      val pntRelativePath = xml \ "parent" \ "relativePath"
      if (pntRelativePath.isEmpty || pntRelativePath.text == "") {
        val pntPom = Pom.find(parentCoordinates._1.get, parentCoordinates._2.get)
        if (pntPom == None)
          ConsoleLogger().warn("Cannot resolve parent definition of [" + pomFile + "]")
        pntPom
      }
      else {
        val pntPath = baseDir + "/" + pntRelativePath.text
        Some(Pom(if (new File(pntPath).isDirectory) pntPath + "/pom.xml" else pntPath))
      }
    }

  // Basic info
  val groupId: String = getText(xml \ "groupId").orElse(parentCoordinates._1).orNull
  val artifactId: String = getText(xml \ "artifactId").orNull
  val ver: String = getText(xml \ "version").orElse(parentCoordinates._3).orNull
  require(groupId != null, "Cannot resolve the groupId of [" + pomFile + "]")
  require(artifactId != null, "Cannot resolve the artifactId of [" + pomFile + "]")
  require(version != null, "Cannot resolve the version of [" + pomFile + "]")

  // Properties
  lazy val properties: PomProperty =
    new PomProperty(
      { // Property tags
        val x = (xml \ "properties").headOption
        if (x == None) Map()
        else Map() ++ 
          x.get.child.map(p => p.label -> p.text)
            .filterNot(_._1 == "#PCDATA")
      },
      // Super project properties,
      parent.map(_.properties),
      // This pom.xml project properties
      xml,
      // Super pom properties
      SuperPom.projectXml(baseDir)
    )

  // Dependencies
  lazy val dependencies: DependencySet = 
    new DependencySet(
      (xml \ "dependencies" \ "dependency" map ( n => parseDependency(n, parent) )) ++
      parent.map(_.dependencies.list).toSeq.flatten
    )

  lazy val dependencyManagement: DependencySet = 
    new DependencySet(xml \ "dependencyManagement" \ "dependencies" \ "dependency" map ( n => parseDependency(n, None) ))

  // Repositories
  lazy val repositories: Seq[(String, String)] = 
    (xml \ "repositories" \ "repository") map (n => ((n \ "id").text, (n \ "url").text))

  // Metadata
  lazy val licenseInfo = (xml \ "licenses" \ "license").map (p => ((p \ "name").text) -> new java.net.URL((p \ "url").text))
  lazy val organizationInfo = xml \ "organization"
  lazy val pomextra = xml \ "developers" ++
    xml \ "contributors" ++
    xml \ "issueManagement" ++
    xml \ "mailingLists" ++
    xml \ "scm"

  // Scala version
  lazy val scalaVer = dependencies.lookup(Common.scalaLibrary).map(_.version)

  // Modules
  lazy val modules: List[String] = 
    (xml \ "modules" \ "module").map { p: NodeSeq => baseDir + "/" + p.text + "/pom.xml" }.toList

  // SBT Models
  lazy val allModules: List[Project] = modules.map(Pom.apply _).flatMap(m => m.project :: m.allModules)

  lazy val project: Project = {
    ConsoleLogger().debug("Converting pom.xml to SBT project")

    val (indeps, exdeps): (List[Project], List[PomDependency]) = {
      val (in, ex) = dependencies.list.partition { dep => 
        val indep = Pom.find(dep.groupId, dep.name)
        if (indep == None) 
          false
        else {
          if (indep.get.ver != dep.version) 
            ConsoleLogger().warn("Inner dependency [" + dep.groupId + ":" + dep.name + "] has incomptiable version with in [" 
              + indep.get.pomFile + "], but ignoring")
          true
        }
      }
      (in.map(d => Pom.find(d.groupId, d.name).get).map(_.project), ex)
    }

    val metadata: Seq[Setting[_]] = Common.projectSettings ++ 
      Seq(
        name := resolveProperty(artifactId),
        organization := resolveProperty(groupId),
        version := resolveProperty(ver),
        description := getText(xml \ "description").getOrElse(artifactId),
        organizationHomepage := getText(organizationInfo \ "url").map(new java.net.URL(_)),
        licenses := licenseInfo,
        javacOptions ++= Common.javacOptions.foldLeft(List[String]()) { (opts: List[String], kv: (String, String)) =>
          val rv = properties apply kv._2
          if (rv == None) opts
          else kv._1 :: rv.get :: opts
        },
        pomExtra := pomextra
      ) ++ 
      (Seq( // Optional metadata
        startYear := getText(xml \ "inceptionYear").map(_.toInt),
        homepage := getText(xml \ "url").map(new java.net.URL(_))
      ).flatten) ++
      scalaVer.map(scalaVersion := _)
      
    val bare = Project(
      id = artifactId, 
      base = new File(baseDir)
    ).settings(metadata: _*)
    val withSubprojs = (bare /: allModules) (_ aggregate _)
    val withInterDeps = (withSubprojs /: indeps) (_ dependsOn _)
    withInterDeps.settings(
      libraryDependencies ++= exdeps map (_.toDependency),
      resolvers ++= repositories.map(r => r._1 at r._2)
    )
  }

  protected def parseDependency(node: NodeSeq, parent: Option[Pom]): PomDependency = {
    val (groupId, name) = (
      resolveProperty((node \ "groupId").text),
      resolveProperty((node \ "artifactId").text)
    )
    require(groupId != "", "groupId is empty")
    require(name != "", "artifactId is empty")

    val fallback: Option[PomDependency] = parent flatMap { _.dependencyManagement.lookup(groupId, name) }
    val version = getText(node \ "version").map(resolveProperty _).orElse(fallback.map(_.version))
    require(version != None, "version is empty, even with parent's dependency management")
    val scope = getText(node \ "scope").map(resolveProperty _).orElse(fallback.flatMap(_.scope))
    val classifier = (node \ "classifier").map(_.text).toList
    val exclusions = (node \ "exclusions" \ "exclusion").map{ex =>
      ((ex \ "groupId").text, (ex \ "artifactId").text)
    } match {
      case Nil => fallback.map(_.exclusions).getOrElse(Nil)
      case exs => exs
    }

    new PomDependency(groupId, name, version.get, scope, classifier, exclusions)
  }

  private def resolveProperty(key: String) = {
    val value = properties.resolve(key)

    val remainKeys = ResolveUtil.findAllKeys(value)
    if (!remainKeys.isEmpty)
      ConsoleLogger().warn("Some keys are not value, [" + remainKeys.mkString(", ") + "]")
    value
  }
}

object Pom {
  private val pomOfFile = collection.mutable.Map[String, Pom]()
  private val pomOfCoord = collection.mutable.Map[String, Pom]()
  private val processing = collection.mutable.Set[String]()

  def apply(pomFilePath: String): Pom = {
    val pomFile = new File(pomFilePath)
    assert(pomFile.exists, "[" + pomFilePath + "] doesn't exist")

    val realPath = pomFile.getCanonicalPath
    assert(!(processing contains realPath), {
      ConsoleLogger().error("Cycle detected between parent-sub modules")
      "parent-child module cycle"
    })

    if (!(pomOfFile contains realPath)) {
      processing += realPath
      val pom = new Pom(pomFile)
      processing -= realPath
      
      pomOfFile += realPath -> pom
      pomOfCoord += (pom.groupId + ":" + pom.artifactId) -> pom
    }

    pomOfFile(realPath)
  }

  def find(group: String, name: String) =
    pomOfCoord.get(group + ":" + name)
}
