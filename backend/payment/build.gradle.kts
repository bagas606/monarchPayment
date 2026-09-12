dependencies {
    api(project(":shared-kernel"))
    api(project(":ledger"))
    api(project(":webhook"))
    api(project(":configuration"))
    implementation("org.springframework.boot:spring-boot-starter-webflux")
}
