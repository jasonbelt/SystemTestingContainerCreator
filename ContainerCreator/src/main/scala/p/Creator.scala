// #Sireum
package p

import org.sireum.Cli.SireumProyekSlangcheckOption
import org.sireum._
import org.sireum.lang.symbol.TypeInfo
import org.sireum.lang.tipe.TypeHierarchy
import org.sireum.logika.Config
import org.sireum.logika.Config.StrictPureMode

object Creator extends App {

  override def main(args: ISZ[String]): Z = {

    val projectRoot = Os.path("/Users/belt/devel/git/isolette-841-f23-MINE/hamr/slang")
    val containers: ISZ[String] = ISZ("isolette.system_tests.john1.SystemTestsJohn__Container")

    val th = getTypeHierachy(projectRoot, containers).get

    for (container <- containers) {
      val qname = ops.StringOps(container).split(c => c == '.')
      val entry = th.typeMap.get(qname).get.asInstanceOf[TypeInfo.Adt]

      val packagePath = ops.ISZOps(entry.name).dropRight(1)
      val basePackageName = packagePath(0)
      val packageName = st"${(packagePath, ".")}".render

      val fullyQualifiedName = container
      val jsonName = st"${(ops.ISZOps(entry.name).drop(1), "")}".render
      val simpleName = ops.ISZOps(entry.name).last

      val testUtilOutputDir = projectRoot / "src" / "test" / "util" /+ packagePath
      val testSystemOutputDir = projectRoot / "src" / "test" / "system" /+ packagePath

      testUtilOutputDir.mkdirAll()
      testSystemOutputDir.mkdirAll()

      val traitName = s"${simpleName}_DSC_Test_Harness"
      val harness =
        st"""// #Sireum
            |
            |package ${packageName}
            |
            |import org.sireum._
            |
            |// Distributed SlangCheck Test Harness for ${fullyQualifiedName}
            |
            |@msig trait $traitName
            |  extends Random.Gen.TestRunner[${fullyQualifiedName}] {
            |
            |  override def toCompactJson(o: ${container}): String = {
            |    return ${basePackageName}.JSON.from${jsonName}(o, T)
            |  }
            |
            |  override def fromJson(json: String): ${fullyQualifiedName} = {
            |    ${basePackageName}.JSON.to${jsonName}(json) match {
            |      case Either.Left(o) => return o
            |      case Either.Right(msg) => halt(msg.string)
            |    }
            |  }
            |
            |  // you'll need to provide implementations for the following:
            |
            |  // override def next(): ${fullyQualifiedName} = {}
            |
            |  // override def test(o: ${fullyQualifiedName}): B = { }
            |}
            |"""

      val harnessPath = testUtilOutputDir / s"${traitName}.scala"
      harnessPath.writeOver(harness.render)


      val exampleName = s"Example_${traitName}"
      val examplePath = testSystemOutputDir / s"${exampleName}.scala"
      val exampleImpl =
        st"""// #Sireum
            |
            |package ${packageName}
            |
            |import org.sireum._
            |
            |@record class ${exampleName} extends ${traitName} {
            |
            |  override def next(): ${fullyQualifiedName} = {
            |    halt("FYTD")
            |  }
            |
            |  override def test(o: ${fullyQualifiedName}): B = {
            |    halt("FYTD")
            |  }
            |}
            |"""

      examplePath.writeOver(exampleImpl.render)
    }

    return 0
  }

  def getTypeHierachy(projectRoot: Os.Path, containers: _root_.org.sireum.ISZ[String]): Option[TypeHierarchy] = {
    val args = s"proyek slangcheck -p isolette -o ${projectRoot}/src/main/util/isolette ${projectRoot}"
    val argsx: ISZ[String] = ops.StringOps(args).split(c => c == ' ') ++ containers

    val o = Cli('/').parseSireum(argsx, 0).get.asInstanceOf[SireumProyekSlangcheckOption]

    val (help, code, path, prj, versions) = org.sireum.cli.Proyek.check(o.json, o.project, Some(1), None(), o.args, o.versions, o.slice)
    if (help) {
      halt(s"${help}")
    } else if (code != 0) {
      halt(s"${code}")
    }

    if (o.args.size < 2) {
      halt(st"Unexpected command line arguments: ${(ops.ISZOps(o.args).drop(1), " ")}".render)
    }

    val dm = project.DependencyManager(
      project = prj,
      versions = versions,
      isJs = F,
      withSource = F,
      withDoc = F,
      javaHome = SireumApi.javaHomeOpt.get,
      scalaHome = SireumApi.scalaHomeOpt.get,
      sireumHome = SireumApi.homeOpt.get,
      cacheOpt = o.cache.map((p: String) => Os.path(p))
    )

    val config = org.sireum.logika.Config(
      smt2Configs = ISZ(),
      parCores = 1,
      sat = F,
      rlimit = 1000000,
      timeoutInMs = 2000,
      charBitWidth = 32,
      intBitWidth = 0,
      useReal = F,
      logPc = F,
      logRawPc = F,
      logVc = F,
      logVcDirOpt = None(),
      dontSplitPfq = F,
      splitAll = F,
      splitContract = F,
      splitIf = F,
      splitMatch = F,
      simplifiedQuery = F,
      checkInfeasiblePatternMatch = T,
      fpRoundingMode = "RNE",
      smt2Seq = F,
      branchPar = org.sireum.logika.Config.BranchPar.All,
      branchParCores = 1,
      atLinesFresh = F,
      interp = F,
      loopBound = 3,
      callBound = 3,
      interpContracts = F,
      elideEncoding = F,
      rawInscription = F,
      smt2Caching = F,
      strictPureMode = StrictPureMode.Default,
      transitionCache = F,
      patternExhaustive = F,
      pureFun = F,
      detailedInfo = F,
      satTimeout = F,
      isAuto = F,
      background = Config.BackgroundMode.Disabled,
      atRewrite = F,
      searchPc = F
    )

    val reporter = logika.ReporterImpl.create(F, F, F, F)

    val mbox: MBox2[HashMap[String, HashMap[String, org.sireum.lang.FrontEnd.Input]], HashMap[String, TypeHierarchy]] = MBox2(HashMap.empty, HashMap.empty)
    val lcode = org.sireum.proyek.Analysis.run(
      root = path,
      outDirName = "out",
      project = prj,
      dm = dm,
      cacheInput = F,
      cacheTypeHierarchy = F,
      mapBox = mbox,
      config = config,
      cache = org.sireum.logika.NoTransitionSmt2Cache.create,
      files = HashSMap.empty,
      filesWatched = F,
      vfiles = ISZ(),
      line = 0,
      par = SireumApi.parCoresOpt(o.par),
      strictAliasing = o.strictAliasing,
      followSymLink = o.symlink,
      all = T,
      disableOutput = F,
      verify = F,
      verbose = o.verbose,
      sanityCheck = T,
      plugins = ISZ(),
      skipMethods = ISZ(),
      skipTypes = ISZ(),
      reporter = reporter
    )

    if (reporter.hasIssue) {
      println()
      reporter.printMessages()
      return None()
    } else if (lcode == 0) {
      println()
      println("Programs are well-typed!")

      val th_ = mbox.value2
      assert(th_.size == 1)

      return Some(th_.values(0))
    } else {
      halt("what")
    }

  }
}
