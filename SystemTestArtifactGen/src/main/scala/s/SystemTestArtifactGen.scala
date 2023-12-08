// #Sireum
package s

import org.sireum.Cli.SireumProyekSlangcheckOption
import org.sireum._
import org.sireum.lang.symbol.TypeInfo
import org.sireum.lang.tipe.TypeHierarchy
import org.sireum.logika.Config
import org.sireum.logika.Config.StrictPureMode
import org.sireum.message.Reporter

object SystemTestArtifactGen extends App {

  val toolName: String = ops.StringOps(this.getClass.getSimpleName).replaceAllLiterally("$", "")

  def usage(): Unit = {
    println("Usage: <dir> <slang-file>+")
  }

  override def main(args: ISZ[String]): Z = {
    if (args.size < 2) {
      usage()
      return 1
    }

    val projectRoot = Os.path(args(0))
    if (!projectRoot.exists || !projectRoot.isDir || !(projectRoot / "bin" / "project.cmd").exists) {
      println("First argument must point to the root of a proyek project")
      usage()
      return 2
    }

    val containers: ISZ[String] = ops.ISZOps(args).slice(1, args.size)
    val reporter = Reporter.create

    val res = run(projectRoot, containers, reporter)

    reporter.printMessages()

    return res + (if (reporter.hasError) 100 else 0)
  }

  def run(projectRoot: Os.Path, containerUris: ISZ[String], reporter: Reporter): Z = {
    val thopt = getTypeHierachy(projectRoot)
    if (thopt.isEmpty) {
      reporter.error(None(), toolName, s"Couldn't generate a type hierarch from ${projectRoot}")
      return 1
    }

    val th = thopt.get

    var fileUris: ISZ[String] = ISZ()
    for (f <- containerUris) {
      var cand: Os.Path = Os.path(f)
      if (!cand.exists) {
        cand = projectRoot / f
      }
      if (cand.exists) {
        fileUris = fileUris :+ cand.toUri
      } else {
        reporter.error(None(), toolName, s"Could not resolve ${f}")
      }
    }

    if (reporter.hasError) {
      return 1
    }

    var containers: ISZ[TypeInfo.Adt] = ISZ()
    for (v <- th.typeMap.values) {
      val temp: TypeInfo = v

      v.posOpt match {
        case Some(v) if ops.ISZOps(fileUris).contains(v.uriOpt.get) =>
          if (!temp.isInstanceOf[TypeInfo.Adt]) {
            reporter.error(temp.posOpt, toolName, s"Only expecting container files to container TypeInfo.Adt, but found ${temp} in ${v.uriOpt.get}")
          } else {
            containers = containers :+ temp.asInstanceOf[TypeInfo.Adt]
          }
        case _ =>
      }
    }

    for (entry <- containers) {

      val packagePath = ops.ISZOps(entry.name).dropRight(1)
      val basePackageName = packagePath(0)
      val packageName = st"${(packagePath, ".")}".render

      val containerFQName = st"${(entry.name, ".")}".render
      val jsonName = st"${(ops.ISZOps(entry.name).drop(1), "")}".render
      val simpleContainerName = ops.ISZOps(entry.name).last

      val testUtilOutputDir = projectRoot / "src" / "test" / "util"
      val testSystemOutputDir = projectRoot / "src" / "test" / "system"

      testUtilOutputDir.mkdirAll()
      testSystemOutputDir.mkdirAll()

      val utilPath = packagePath :+ s"${simpleContainerName}_Util"
      val utilName = st"${(utilPath, ".")}".render
      val simpleUtilName = ops.ISZOps(utilPath).last

      var profileEntries: ISZ[ST] = ISZ()
      var freshLibs: ISZ[ST] = ISZ()
      var nextEntriesViaProfile: ISZ[ST] = ISZ()
      for (v <- entry.vars.entries) {
        val typ = v._2.typedOpt.get.asInstanceOf[org.sireum.lang.ast.Typed.Name]
        val nextMethodName = st"next${(ops.ISZOps(typ.ids).drop(1), "")}".render

        freshLibs = freshLibs :+ st"${v._1} = ${simpleUtilName}.freshRandomLib"
        profileEntries = profileEntries :+ st"var ${v._1} : RandomLib"
        nextEntriesViaProfile = nextEntriesViaProfile :+ st"${v._1} = profile.${v._1}.${nextMethodName}()"
      }

      val doNotEdit = s"// Do not edit this file as it will be overwritten if $toolName is rerun"

      val util =
        st"""// #Sireum
            |
            |package ${packageName}
            |
            |import org.sireum._
            |import isolette._
            |import org.sireum.Random.Impl.Xoshiro256
            |
            |$doNotEdit
            |
            |object ${simpleUtilName} {
            |
            |  def freshRandomLib: RandomLib = {
            |    return RandomLib(Random.Gen64Impl(Xoshiro256.createSeed(${simpleUtilName}I.getSeed)))
            |  }
            |}
            |
            |@ext object ${simpleUtilName}I {
            |  def getSeed: U64 = $$
            |}
            |"""
      val t1 = testUtilOutputDir /+ packagePath / s"${simpleUtilName}.scala"
      t1.writeOver(util.render)
      reporter.info(None(), toolName, s"Wrote: ${t1}")

      val utilI =
        st"""package ${packageName}
            |
            |import org.sireum._
            |import ${basePackageName}._
            |
            |$doNotEdit
            |
            |object ${simpleUtilName}I_Ext {
            |  def getSeed: U64 = {
            |    val rand = new java.util.Random()
            |    rand.setSeed(rand.nextLong())
            |    return U64(rand.nextLong())
            |  }
            |}
            |"""
      val t2 = testUtilOutputDir /+ packagePath / s"${simpleUtilName}I_Ext.scala"
      t2.writeOver(utilI.render)
      reporter.info(None(), toolName, s"Wrote: ${t2}")

      val profilePath = packagePath :+ s"${simpleContainerName}_Profile"
      val profileFQName = st"${(profilePath, ".")}".render
      val simpleProfileName = ops.ISZOps(profilePath).last

      val profiles =
        st"""// #Sireum
            |
            |package ${packageName}
            |
            |import org.sireum._
            |import ${basePackageName}._
            |
            |$doNotEdit
            |
            |object ${simpleProfileName} {
            |
            |  // a call to next may result in an AssertionError which is an indication that
            |  // SlangCheck was unable to satisfy a field's filter.  Consider using
            |  // nextH instead
            |  def next(profile: ${simpleProfileName}): ${simpleContainerName} = {
            |    return ${simpleContainerName} (
            |      ${(nextEntriesViaProfile, ",\n")}
            |    )
            |  }
            |
            |  // nextH will return None() if SlangCheck is unable to satisfy a field's filter
            |  def nextH(profile: ${simpleProfileName}): Option[${simpleContainerName}] = {
            |    return ${simpleProfileName}I.next(profile)
            |  }
            |
            |  def getDefaultProfile: ${simpleProfileName} = {
            |    return ${simpleProfileName} (
            |      name = "Default ${simpleProfileName} Profile",
            |      numTests = 100,
            |      numTestVectorGenRetries = 100,
            |
            |      ${(freshLibs, ",\n")}
            |    )
            |  }
            |}
            |
            |@ext object ${simpleProfileName}I {
            |  def next(profile: ${simpleProfileName}): Option[${simpleContainerName}] = $$
            |}
            |
            |@record class $simpleProfileName (
            |  var name: String,
            |  var numTests: Z,
            |  var numTestVectorGenRetries: Z,
            |
            |  ${(profileEntries, ",\n")}
            |)
            |"""

      val t3 = (testUtilOutputDir /+ packagePath) / s"${simpleProfileName}.scala"
      t3.writeOver(profiles.render)
      reporter.info(None(), toolName, s"Wrote: ${t3}")

      val profileI =
        st"""package $packageName
            |
            |import org.sireum._
            |import ${basePackageName}._
            |
            |$doNotEdit
            |
            |object ${simpleProfileName}I_Ext {
            |  def next(profile: ${simpleProfileName}): Option[${simpleContainerName}] = {
            |    try {
            |      return Some(${simpleContainerName} (
            |        ${(nextEntriesViaProfile, ",\n")}))
            |    } catch {
            |      case e: AssertionError =>
            |        // SlangCheck was unable to satisfy a datatype's filter
            |        return None()
            |    }
            |  }
            |}
            |"""

      val t3_1 = (testUtilOutputDir /+ packagePath) / s"${simpleProfileName}I_Ext.scala"
      t3_1.writeOver(profileI.render)
      reporter.info(None(), toolName, s"Wrote: ${t3_1}")

      val simpleDSCTraitName = s"${simpleContainerName}_DSC_Test_Harness"
      val harness =
        st"""// #Sireum
            |
            |package ${packageName}
            |
            |import org.sireum._
            |import ${basePackageName}._
            |import org.sireum.Random.Impl.Xoshiro256
            |
            |$doNotEdit
            |
            |// Distributed SlangCheck Test Harness for ${containerFQName}
            |
            |@msig trait $simpleDSCTraitName
            |  extends Random.Gen.TestRunner[${containerFQName}] {
            |
            |  override def toCompactJson(o: ${containerFQName}): String = {
            |    return ${basePackageName}.JSON.from${jsonName}(o, T)
            |  }
            |
            |  override def fromJson(json: String): ${containerFQName} = {
            |    ${basePackageName}.JSON.to${jsonName}(json) match {
            |      case Either.Left(o) => return o
            |      case Either.Right(msg) => halt(msg.string)
            |    }
            |  }
            |
            |  // you'll need to provide implementations for the following:
            |
            |  // override def next(): ${containerFQName} = {}
            |
            |  // override def test(o: ${containerFQName}): B = { }
            |}
            |"""
      val t4 = testUtilOutputDir /+ packagePath / s"${simpleDSCTraitName}.scala"
      t4.writeOver(harness.render)
      reporter.info(None(), toolName, s"Wrote: ${t4}")



      val slangCheckTraitPath = packagePath :+ s"${simpleContainerName}_SlangCheck"
      val slangCheckTraitFQName = st"${(slangCheckTraitPath, ".")}".render
      val simpleSlangCheckTraitName = ops.ISZOps(slangCheckTraitPath).last

      val slangCheckTrait =
        st"""package ${packageName}
            |
            |import org.sireum._
            |import ${basePackageName}._
            |
            |${doNotEdit}
            |
            |trait ${simpleSlangCheckTraitName}
            |  extends SystemTestSuite {
            |
            |  case class NameProvider(name: String,
            |                          function: (Any, Any) => B)
            |
            |  case class TestRow(testMethod: NameProvider,
            |                     profile: ${simpleProfileName},
            |                     preStateCheck: (Any => B),
            |                     property: NameProvider)
            |
            |  def next(profile: ${simpleProfileName}): Option[${simpleContainerName}] = {
            |    return ${simpleProfileName}.nextH(profile)
            |  }
            |
            |  def freshRandomLib: RandomLib = {
            |    return ${simpleUtilName}.freshRandomLib
            |  }
            |
            |  def getProfiles: MSZ[${simpleProfileName}] = {
            |    return MSZ(getDefaultProfile)
            |  }
            |
            |  //------------------------------------------------
            |  //  Test Vector Profiles
            |  //
            |  //   ..eventually auto-generated from a descriptor
            |  //     of injection vector
            |  //------------------------------------------------
            |
            |  def getDefaultProfile: ${simpleProfileName} = {
            |    return ${simpleProfileName}.getDefaultProfile
            |  }
            |
            |  def disableLogsAndGuis(): Unit = {
            |
            |    // disable the various guis
            |    System.setProperty("java.awt.headless", "true")
            |
            |    // suppress ART's log stream
            |    art.ArtNative_Ext.logStream = new java.io.PrintStream(new java.io.OutputStream {
            |      override def write(b: Int): Unit = {}
            |    })
            |
            |    // suppress the static scheduler's log stream
            |    art.scheduling.static.StaticSchedulerIO_Ext.logStream = new java.io.PrintStream(new java.io.OutputStream {
            |      override def write(b: Int): Unit = {}
            |    })
            |  }
            |}
            |"""

      val t6 = (testUtilOutputDir /+ packagePath / s"${simpleSlangCheckTraitName}.scala")
      t6.writeOver(slangCheckTrait.render)
      reporter.info(None(), toolName, s"Wrote: ${t6}")

      val exampleSlangCheckPath = packagePath :+ s"Example_${simpleContainerName}_Test_wSlangCheck"
      val exampleSlangCheckName = st"${(exampleSlangCheckPath, ".")}".render
      val simpleExampleSlangCheckName = ops.ISZOps(exampleSlangCheckPath).last

      val exampleSlangCheck =
        st"""package $packageName
            |
            |import org.sireum._
            |import art.scheduling.static._
            |import art.Art
            |import ${basePackageName}._
            |
            |$doNotEdit
            |
            |class ${simpleExampleSlangCheckName}
            |  extends ${simpleSlangCheckTraitName} {
            |
            |  //===========================================================
            |  //  S c h e d u l a r     and    S t e p p e r     Configuration
            |  //===========================================================
            |
            |  // note: this is overriding SystemTestSuite's 'def scheduler: Scheduler'
            |  //       abstract method
            |  //var scheduler: StaticScheduler = Schedulers.getStaticSchedulerH(MNone())
            |  var scheduler: StaticScheduler = Schedulers.getStaticScheduler(
            |    Schedulers.defaultStaticSchedule,
            |    Schedulers.defaultDomainToBridgeIdMap,
            |    Schedulers.threadNickNames,
            |    ISZCommandProvider(ISZ()))
            |
            |  def compute(isz: ISZ[Command]): Unit = {
            |    scheduler = scheduler(commandProvider = ISZCommandProvider(isz :+ Stop()))
            |
            |    Art.computePhase(scheduler)
            |  }
            |
            |  override def beforeEach(): Unit = {
            |
            |    // uncomment the following to disable the various guis and to suppress the log streams
            |    //disableLogsAndGuis()
            |
            |    super.beforeEach()
            |  }
            |
            |  //===========================================================
            |  //  S l a n g   C h e c k    Infrastructure
            |  //===========================================================
            |
            |  val maxTests = 100
            |  var verbose: B = T
            |
            |  val testMatrix: Map[String, TestRow] = Map.empty ++ ISZ(
            |    "testFamilyName" ~> TestRow(
            |      testMethod = NameProvider("Schema-Name", ((input_container: Any, output_container: Any) => T).asInstanceOf[(Any, Any) => B]),
            |      profile =getDefaultProfile,
            |      preStateCheck = ((container: Any) => T).asInstanceOf[Any => B],
            |      property = NameProvider("Property-Name", ((input_container: Any, output_container: Any) => T).asInstanceOf[(Any, Any) => B])
            |    )
            |  )
            |
            |  for (testRow <- testMatrix.entries) {
            |    run(testRow._1, testRow._2)
            |  }
            |
            |  def genTestName(testFamilyName: String, testRow: TestRow): String = {
            |    return s"$${testFamilyName}: $${testRow.testMethod.name}: $${testRow.property.name}: $${testRow.profile.name}"
            |  }
            |
            |  def run(testFamilyName: String, testRow: TestRow): Unit = {
            |
            |    for (i <- 0 until maxTests) {
            |      val testName = s"$${genTestName(testFamilyName, testRow)}_$$i"
            |      this.registerTest(testName) {
            |        var retry: B = T
            |        var j: Z = 0
            |
            |        while (j < testRow.profile.numTestVectorGenRetries && retry) {
            |          if (verbose && j > 0) {
            |            println(s"Retry $$j:")
            |          }
            |
            |          next(testRow.profile) match {
            |            case Some(container) =>
            |              if (!testRow.preStateCheck(container)) {
            |                // retry
            |              } else {
            |                testRow.testMethod.function(container, testRow.property.function)
            |                retry = F
            |              }
            |            case _ =>
            |          }
            |          j = j + 1
            |        }
            |      }
            |    }
            |  }
            |}"""

      val t7 = (testSystemOutputDir /+ packagePath / s"${simpleExampleSlangCheckName}.scala")
      t7.writeOver(exampleSlangCheck.render)
      reporter.info(None(), toolName, s"Wrote: ${t7}")


      val exampleDSCPath = packagePath :+ s"Example_${simpleDSCTraitName}"
      val exampleDSCFQName = st"${(exampleDSCPath, ".")}".render
      val simpleExampleDSCName = ops.ISZOps(exampleDSCPath).last

      val exampleDSCImpl =
        st"""package ${packageName}
            |
            |import org.sireum._
            |import ${basePackageName}._
            |
            |$doNotEdit
            |
            |object TestIt_${simpleExampleDSCName} extends App {
            |
            |  override def main(args: ISZ[String]): Z = {
            |    System.setProperty("TEST_FAMILY_NAME", "<key from testMatrix>")
            |
            |    val instance = new ${simpleExampleDSCName}()
            |
            |    // simulate DSC calling next
            |    val container = instance.next()
            |
            |    // simulate DSC calling test
            |    val result = instance.test(container)
            |
            |    return if(result) 0 else 1
            |  }
            |}
            |
            |class $simpleExampleDSCName
            |  extends $simpleExampleSlangCheckName
            |  with $simpleDSCTraitName {
            |
            |  override def next(): ${containerFQName} = {
            |    val testRow = testMatrix.get(getTestId()).get
            |    return ${simpleProfileName}.next(testRow.profile)
            |  }
            |
            |  override def test(o: ${containerFQName}): B = {
            |    val testId = getTestId()
            |    val testRow = testMatrix.get(testId).get
            |
            |    println(genTestName(testId, testRow))
            |
            |    disableLogsAndGuis()
            |
            |    super.beforeEach()
            |
            |    if (!testRow.preStateCheck(o)) {
            |      println(s"Didn't pass pre state check $${o}")
            |
            |      DSC_RecordUnsatPre.report(toCompactJson(o))
            |
            |      return T
            |    } else {
            |
            |      val result = testRow.testMethod.function(o, testRow.property.function)
            |
            |      this.afterEach()
            |
            |      return result
            |    }
            |  }
            |
            |  def getTestId(): String = {
            |    Os.prop("TEST_FAMILY_NAME") match {
            |      case Some(v) => return v
            |      case _ =>
            |        Os.env("TEST_FAMILY_NAME") match {
            |          case Some(v) => return v
            |          case _ =>
            |        }
            |    }
            |    halt("TEST_FAMILY_NAME not defined")
            |  }
            |
            |  override def string: String = toString
            |
            |  override def $$clonable: Boolean = F
            |
            |  override def $$clonable_=(b: Boolean): org.sireum.$$internal.MutableMarker = this
            |
            |  override def $$owned: Boolean = F
            |
            |  override def $$owned_=(b: Boolean): org.sireum.$$internal.MutableMarker = this
            |
            |  override def $$clone: org.sireum.$$internal.MutableMarker = this
            |}
            |"""
      val t5 = (testSystemOutputDir /+ packagePath / s"${simpleExampleDSCName}.scala")
      t5.writeOver(exampleDSCImpl.render)
      reporter.info(None(), toolName, s"Wrote: ${t5}")


    } // end of for loop
    return 0
  }

  def getTypeHierachy(projectRoot: Os.Path): Option[TypeHierarchy] = {
    val args = s"proyek slangcheck -p isolette -o ${projectRoot}/src/main/util/isolette ${projectRoot}"
    val argsx: ISZ[String] = ops.StringOps(args).split(c => c == ' ')

    val o = Cli('/').parseSireum(argsx, 0).get.asInstanceOf[SireumProyekSlangcheckOption]

    val (help, code, path, prj, versions) = org.sireum.cli.Proyek.check(o.json, o.project, Some(1), None(), o.args, o.versions, o.slice)
    if (help) {
      halt(s"${help}")
    } else if (code != 0) {
      halt(s"${code}")
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