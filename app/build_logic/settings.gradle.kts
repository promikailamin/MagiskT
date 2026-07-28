/**
 * Build-logic module settings.
 * Shares the same version catalog (libs.versions.toml) as the main project.
 */
dependencyResolutionManagement {
    versionCatalogs {
        create("libs") {
            from(files("../gradle/libs.versions.toml"))
        }
    }
}
