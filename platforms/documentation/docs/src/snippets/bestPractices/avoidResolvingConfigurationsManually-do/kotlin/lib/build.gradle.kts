plugins {
    `java-library`
}

// tag::do-this[]
tasks.named("compileJava").configure {
    doLast {
        logger.lifecycle("Lib was compiled")
    }
}
// end::do-this[]
