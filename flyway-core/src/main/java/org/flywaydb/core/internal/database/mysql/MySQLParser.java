/*
 * Copyright 2010-2020 Boxfuse GmbH
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *         http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.flywaydb.core.internal.database.mysql;

import org.flywaydb.core.api.configuration.Configuration;
import org.flywaydb.core.internal.parser.*;

import java.io.IOException;
import java.util.Arrays;
import java.util.List;
import java.util.regex.Pattern;

public class MySQLParser extends Parser {
    private static final char ALTERNATIVE_SINGLE_LINE_COMMENT = '#';

    public MySQLParser(Configuration configuration, ParsingContext parsingContext) {
        super(configuration, parsingContext, 8);
    }

    @Override
    protected void resetDelimiter(ParserContext context) {
        // Do not reset delimiter as delimiter changes survive beyond a single statement
    }

    @Override
    protected Token handleKeyword(PeekingReader reader, ParserContext context, int pos, int line, int col, String keyword) throws IOException {
        if (keywordIs("DELIMITER", keyword)) {
            String text = reader.readUntilExcluding('\n', '\r').trim();
            return new Token(TokenType.NEW_DELIMITER, pos, line, col, text, text, context.getParensDepth());
        }
        return super.handleKeyword(reader, context, pos, line, col, keyword);
    }

    @Override
    protected char getIdentifierQuote() {
        return '`';
    }

    @Override
    protected char getAlternativeStringLiteralQuote() {
        return '"';
    }

    @Override
    protected boolean isSingleLineComment(String peek, ParserContext context, int col) {
        return (super.isSingleLineComment(peek, context, col)
                // Normally MySQL treats # as a comment, but this may have been overridden by DELIMITER # directive
                || (peek.charAt(0) == ALTERNATIVE_SINGLE_LINE_COMMENT && !isDelimiter(peek, context, col)));
    }

    @Override
    protected Token handleStringLiteral(PeekingReader reader, ParserContext context, int pos, int line, int col) throws IOException {
        reader.swallow();
        reader.swallowUntilExcludingWithEscape('\'', true, '\\');
        return new Token(TokenType.STRING, pos, line, col, null, null, context.getParensDepth());
    }

    @Override
    protected Token handleAlternativeStringLiteral(PeekingReader reader, ParserContext context, int pos, int line, int col) throws IOException {
        reader.swallow();
        reader.swallowUntilExcludingWithEscape('"', true, '\\');
        return new Token(TokenType.STRING, pos, line, col, null, null, context.getParensDepth());
    }

    @Override
    protected Token handleCommentDirective(PeekingReader reader, ParserContext context, int pos, int line, int col) throws IOException {
        reader.swallow(2);
        String text = reader.readUntilExcluding("*/");
        reader.swallow(2);
        return new Token(TokenType.MULTI_LINE_COMMENT_DIRECTIVE, pos, line, col, text, text, context.getParensDepth());
    }

    @Override
    protected boolean isCommentDirective(String text) {
        return text.length() >= 8
                && text.charAt(0) == '/'
                && text.charAt(1) == '*'
                && text.charAt(2) == '!'
                && isDigit(text.charAt(3))
                && isDigit(text.charAt(4))
                && isDigit(text.charAt(5))
                && isDigit(text.charAt(6))
                && isDigit(text.charAt(7));
    }

    // These words increase the block depth - unless preceded by END (in which case the END will decrease the block depth)
    // See: https://dev.mysql.com/doc/refman/8.0/en/flow-control-statements.html
    private static final List<String> CONTROL_FLOW_KEYWORDS = Arrays.asList("IF", "LOOP", "CASE", "REPEAT", "WHILE");

    private static final Pattern CREATE_IF_NOT_EXISTS = Pattern.compile(
            ".*CREATE\\s([^\\s]+\\s){1,2}IF\\sNOT\\sEXISTS");
    private static final Pattern DROP_IF_EXISTS = Pattern.compile(
            ".*DROP\\s([^\\s]+\\s){1,2}IF\\sEXISTS");

    @Override
    protected void adjustBlockDepth(ParserContext context, List<Token> tokens, Token keyword, PeekingReader reader) throws IOException {
        String keywordText = keyword.getText();

        int parensDepth = keyword.getParensDepth();

        if (("IF".equals(keywordText) || "REPEAT".equals(keywordText)) && '(' == reader.peekNextNonWhitespace()) {
            // do not enter a block if this is the function version of these keywords
            return;
        }
        if (context.getBlockDepth() > 0 && "EXISTS".equals(keywordText) && '(' == reader.peekNextNonWhitespace() && "IF".equals(tokens.get(tokens.size()-1).getText())) {
            // if this a IF EXISTS(SELECT then drop out of the block entered by the preceding IF
            context.decreaseBlockDepth();
        }
        else if ("BEGIN".equals(keywordText)
               || (CONTROL_FLOW_KEYWORDS.contains(keywordText) && !lastTokenIs(tokens, parensDepth, "END"))) {
            context.increaseBlockDepth();
        } else if ("END".equals(keywordText)
                || doTokensMatchPattern(tokens, keyword, CREATE_IF_NOT_EXISTS)
                || doTokensMatchPattern(tokens, keyword, DROP_IF_EXISTS)) {
            context.decreaseBlockDepth();
        }
    }
}