dependencies {
    api(project(":shared-kernel"))
    api(project(":ledger"))
    api(project(":settlement"))
    api(project(":payment"))
    api(project(":provider"))
    api("org.springframework.boot:spring-boot-starter-data-jpa")
}
