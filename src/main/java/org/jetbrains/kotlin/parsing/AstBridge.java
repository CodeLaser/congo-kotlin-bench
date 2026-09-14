package org.jetbrains.kotlin.parsing;

import com.intellij.lang.ASTNode;
import com.intellij.lang.PsiBuilder;
import com.intellij.lang.PsiBuilderFactory;
import org.jetbrains.kotlin.lexer.KotlinLexer;

/**
 * The compiler's parse with the PSI layer stripped off: exactly what {@code KotlinLightParser.buildLightTree}
 * does (fresh parser definition + lexer, {@code createForTopLevelNonLazy}, {@code parseFile}), except that the
 * last call materialises the AST ({@code getTreeBuilt}) instead of returning the flyweight light tree.
 * <p>
 * No PsiFile, no FileViewProvider, no LightVirtualFile, no PSI wrappers, and no lazy blocks: function bodies
 * and lambdas are parsed in the same pass as everything else, as in CongoCC.
 * <p>
 * Lives in this package because {@code KotlinParsing.createForTopLevelNonLazy} and {@code parseFile} are
 * package-private.
 */
public final class AstBridge {
    private AstBridge() {
    }

    public static ASTNode parse(CharSequence text) {
        PsiBuilder builder = PsiBuilderFactory.getInstance()
                .createBuilder(new KotlinParserDefinition(), new KotlinLexer(), text);
        KotlinParsing.createForTopLevelNonLazy(new SemanticWhitespaceAwarePsiBuilderImpl(builder)).parseFile();
        return builder.getTreeBuilt();
    }
}
