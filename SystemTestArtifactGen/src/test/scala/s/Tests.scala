package s

import org.sireum._
import org.sireum.message.Reporter

class Tests extends org.sireum.test.TestSuite {

  "isolette" in {
    val root = Os.home / "devel/git/isolette-841-f23-MINE/hamr/slang"

    val containers: ISZ[String] =
      ISZ("src/main/util/isolette/system_tests/rst/Regulate_Subsystem_Containers.scala",
          "src/main/util/isolette/system_tests/john1/SystemTestsJohn__Container.scala")

    val reporter = Reporter.create
    val result = s.SystemTestArtifactGen.run(root, containers, reporter)

    reporter.printMessages()

    assert(result == 0 && !reporter.hasError)
  }
}
