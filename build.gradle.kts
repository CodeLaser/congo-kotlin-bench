plugins {
    java
    application
}

repositories {
    mavenCentral()
}

dependencies {
    // The compiler's own parser: KotlinParsing (hand-written recursive descent) + the JFlex KotlinLexer,
    // driven through IntelliJ's PsiBuilder. Same version maddi-kotlin-k2 uses.
    implementation("org.jetbrains.kotlin:kotlin-compiler:2.4.0")
    // CongoCC's generated Kotlin parser, built from congo-grammars/kotlin (see README.md for how).
    implementation(files("libs/congo-kotlin.jar"))
}

tasks.withType<JavaCompile> {
    options.release = 21
}

application {
    mainClass = "bench.ParserBench"
}
