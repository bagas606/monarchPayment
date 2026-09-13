dependencies {
    implementation(project(":shared-kernel"))
    implementation(project(":ledger"))
    implementation(project(":settlement"))
    implementation(project(":payment"))
    implementation(project(":provider"))
    api("org.springframework.boot:spring-boot-starter-data-jpa")
}
