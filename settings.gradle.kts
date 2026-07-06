/**
 * ===============================================================================
 * 功能：StringFogPro 单模块工程设置。
 * 函数简介：单模块仓——仓库根目录即库本体，无 include；仅声明依赖仓库。
 * ===============================================================================
 */

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        mavenCentral()
    }
}

rootProject.name = "StringFogPro"
