plugins {
    id("org.springframework.boot")
}

dependencies {
    implementation(project(":shared-kernel"))
    implementation(project(":channel"))
    implementation(project(":partner"))
    implementation(project(":catalog"))
    implementation(project(":configuration"))
    implementation(project(":audit"))
    implementation(project(":provider"))
    implementation(project(":pricing"))
    implementation(project(":routing"))
    implementation(project(":decomposition"))
    implementation(project(":payment"))
    implementation(project(":ledger"))
    implementation(project(":settlement"))
    implementation(project(":order"))
    implementation(project(":fulfillment"))
    implementation(project(":reconciliation"))
    implementation(project(":webhook"))
    implementation(project(":admin"))

    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("org.springframework.boot:spring-boot-starter-data-jpa")
    implementation("org.springframework.boot:spring-boot-starter-security")
    implementation("org.springframework.boot:spring-boot-starter-actuator")
    implementation("org.springframework.boot:spring-boot-starter-validation")
    implementation("org.flywaydb:flyway-core")
    implementation("org.flywaydb:flyway-database-postgresql")
    runtimeOnly("org.postgresql:postgresql")

    testImplementation("org.springframework.security:spring-security-test")
    testImplementation("com.tngtech.archunit:archunit-junit5:1.3.0")
}
