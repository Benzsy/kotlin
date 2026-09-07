plugins {
    id("common-configuration")
    id("com.autonomousapps.dependency-analysis")
    kotlin("jvm")
}

dependencies {
    api(project(":compiler:psi:psi-api"))
    api(project(":compiler:psi:psi-impl"))
    api(project(":core:deserialization.common"))
    api(project(":core:deserialization.common.jvm"))
    implementation(project(":core:deserialization"))
    implementation(project(":core:descriptors"))
    implementation(project(":core:compiler.common.jvm"))
    implementation(project(":kotlin-util-klib"))
    implementation(project(":analysis:analysis-internal-utils"))
    implementation(project(":analysis:analysis-api"))
    testImplementation(testFixtures(project(":compiler:tests-common-new")))

    api(intellijCore())
}

sourceSets {
    "main" { projectDefault() }
    "test" {}
}
