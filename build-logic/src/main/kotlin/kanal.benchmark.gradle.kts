plugins {
    id("me.champeau.jmh")
}

jmh {
    jmhVersion.set(libs.versions.jmh.get())
    resultFormat.set("JSON")

    if (project.hasProperty("jmhInclude")) {
        val filter = project.property("jmhInclude") as String
        includes.add(filter)
        resultsFile.set(layout.buildDirectory.file("reports/jmh/results-$filter.json"))
    } else {
        resultsFile.set(layout.buildDirectory.file("reports/jmh/results.json"))
    }
}
