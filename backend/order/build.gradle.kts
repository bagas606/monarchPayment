dependencies {
    implementation(project(":shared-kernel"))
    implementation(project(":payment"))
    implementation(project(":decomposition"))
    implementation(project(":channel"))
    implementation(project(":partner"))
    implementation(project(":ledger"))
    implementation(project(":configuration"))
    implementation(project(":audit"))
    api("org.springframework.boot:spring-boot-starter-data-jpa")
}
