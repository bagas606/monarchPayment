dependencies {
    implementation(project(":shared-kernel"))
    implementation(project(":routing"))
    implementation(project(":catalog"))
    implementation(project(":pricing"))
    implementation(project(":configuration"))
    api("org.springframework.boot:spring-boot-starter-data-jpa")
}
