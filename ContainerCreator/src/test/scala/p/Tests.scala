package p

import org.sireum._

class Tests extends org.sireum.test.TestSuite {

  "isolette" in {
    val root = Os.path("/Users/belt/devel/git/isolette-841-f23-MINE/hamr/slang")
    val containers: ISZ[String] = ISZ("isolette.system_tests.john1.SystemTestsJohn__Container")

    p.Creator.run(root, containers)
  }
}
