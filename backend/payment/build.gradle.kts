dependencies {
    implementation(project(":shared-kernel"))
    implementation(project(":ledger"))
    implementation(project(":webhook"))
    implementation(project(":configuration"))
    implementation("org.springframework.boot:spring-boot-starter-webflux")
}
