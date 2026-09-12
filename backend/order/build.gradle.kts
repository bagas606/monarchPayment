dependencies {
    api(project(":shared-kernel"))
    api(project(":payment"))
    api(project(":decomposition"))
    api(project(":channel"))
    api(project(":partner"))
    api(project(":ledger"))
    api(project(":configuration"))
    api(project(":audit"))
    api("org.springframework.boot:spring-boot-starter-data-jpa")
}
