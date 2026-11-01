package com.dujiteng.ragentlearning.knowledge.chunk;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.dujiteng.ragentlearning.common.ChatApiException;
import java.util.Random;
import org.junit.jupiter.api.Test;

class TextSplitterTest {
    private final TextSplitter splitter = new TextSplitter();

    @Test
    void keepsShortTextAndOffsets() {
        String text = "第一段🙂\n\n第二段，原文不做额外清洗。";
        var rows = splitter.split(text, 800, 120);
        assertEquals(1, rows.size());
        assertEquals(text, rows.get(0).content());
        assertEquals(0, rows.get(0).index());
        assertEquals(text.codePointCount(0, text.length()), rows.get(0).endOffset());
    }

    @Test
    void exactSizeHasNoExtraOverlapOnlyTail() {
        assertEquals(1, splitter.split("🙂".repeat(200), 200, 100).size());
    }

    @Test
    void hardSplitPreservesOverlapAndAllUnicodeCharacters() {
        String text = "中文🙂ABC".repeat(300);
        var rows = splitter.split(text, 200, 40);
        assertEquals(200, rows.get(0).endOffset());
        assertEquals(160, rows.get(1).startOffset());
        verifyCoverage(text, 200, 40);
    }

    @Test
    void prefersParagraphBoundaryWithinBudget() {
        var rows = splitter.split("甲".repeat(120) + "\n\n" + "乙".repeat(300), 200, 20);
        assertEquals(122, rows.get(0).endOffset());
        assertTrue(rows.get(0).content().endsWith("\n\n"));
    }

    @Test
    void prefersSentenceEndAndKeepsPunctuation() {
        var rows = splitter.split("甲".repeat(139) + "。" + "乙".repeat(300), 200, 20);
        assertEquals(140, rows.get(0).endOffset());
        assertTrue(rows.get(0).content().endsWith("。"));
    }

    @Test
    void emptyTextHasNoChunks() {
        assertTrue(splitter.split(null, 800, 120).isEmpty());
        assertTrue(splitter.split(" \n\t", 800, 120).isEmpty());
    }

    @Test
    void rejectsInvalidBudgets() {
        for (int[] pair : new int[][]{{199, 0}, {4001, 0}, {200, -1}, {200, 101}}) {
            var error = assertThrows(ChatApiException.class, () -> splitter.split("正文", pair[0], pair[1]));
            assertEquals(400, error.status());
        }
    }

    @Test
    void rejectsExcessiveChunkCount() {
        var error = assertThrows(ChatApiException.class, () -> splitter.split("字".repeat(1_000_200), 200, 0));
        assertEquals("DOCUMENT_TOO_MANY_CHUNKS", error.code());
    }

    @Test
    void mixedTextHasNoLostOrBrokenCharactersAcrossBudgets() {
        var random = new Random(20261031L);
        String[] choices = {"中文", "🙂", "。", "\n", "\n\n", "word. ", "42", "👩‍💻"};
        for (int run = 0; run < 80; run++) {
            var text = new StringBuilder();
            for (int i = 0; i < 1000; i++) text.append(choices[random.nextInt(choices.length)]);
            text.append("结束");
            int size = 200 + random.nextInt(601);
            verifyCoverage(text.toString(), size, random.nextInt(size / 2 + 1));
        }
    }

    private void verifyCoverage(String text, int size, int overlap) {
        int[] original = text.codePoints().toArray();
        boolean[] covered = new boolean[original.length];
        int previousStart = -1;
        int index = 0;
        for (var row : splitter.split(text, size, overlap)) {
            assertEquals(index++, row.index());
            assertTrue(row.startOffset() > previousStart);
            previousStart = row.startOffset();
            assertTrue(row.endOffset() - row.startOffset() <= size);
            assertEquals(new String(original, row.startOffset(), row.endOffset() - row.startOffset()), row.content());
            for (int i = row.startOffset(); i < row.endOffset(); i++) covered[i] = true;
        }
        for (int i = 0; i < covered.length; i++) {
            if (!Character.isWhitespace(original[i])) assertTrue(covered[i], "原文字符遗漏：" + i);
        }
    }
}
