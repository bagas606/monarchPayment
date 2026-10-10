// A Gradle build of its own, deliberately NOT a module of `backend`'s. PPOB1 is a different
// company's system in this architecture (PRD Section 23 is the contract between the two), and
// including it in `backend/settings.gradle.kts` would put it inside the Section 20.2 module graph
// that `ArchUnitRulesTest` enforces — where it would either have to declare edges it should not
// have, or be an orphan module that nothing depends on and that can still see every internal class
// on the compile classpath.
//
// The separation is what makes this a simulator rather than a second entry point: PPOB1 can only
// reach PPOB2 over signed HTTP, exactly as a real reseller does, because it has no other way in.
rootProject.name = "ppob1-sim"
