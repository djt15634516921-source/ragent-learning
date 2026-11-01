package com.dujiteng.ragentlearning.knowledge.chunk;

import com.dujiteng.ragentlearning.common.ChatApiException;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Component;

/** 对解析后的原文切分，位置与长度统一按 Unicode 码点计算，保留原文内容。 */
@Component
public class TextSplitter {
    private static final int MAX_CHUNKS = 5_000;

    public List<Segment> split(String text, int chunkSize, int overlap) {
        validate(chunkSize, overlap);
        if (text == null || text.isBlank()) return List.of();
        int[] points = text.codePoints().toArray();
        List<Segment> result = new ArrayList<>();
        int start = 0;
        while (start < points.length) {
            int end = Math.min(start + chunkSize, points.length);
            if (end < points.length) {
                // 边界回退后仍保证推进，避免大重叠配合短句造成大量微小片段。
                int minimum = start + Math.max(chunkSize / 2, overlap + chunkSize / 4);
                end = boundary(points, minimum, end);
            }
            String content = new String(points, start, end - start);
            if (!content.isBlank()) {
                result.add(new Segment(result.size(), start, end, content));
                if (result.size() > MAX_CHUNKS) {
                    throw new ChatApiException(422, "DOCUMENT_TOO_MANY_CHUNKS", "片段超过 5000 个，请增大单块长度或减小重叠");
                }
            }
            if (end == points.length) break;
            start = end - overlap;
        }
        return List.copyOf(result);
    }

    public static void validate(int chunkSize, int overlap) {
        if (chunkSize < 200 || chunkSize > 4_000 || overlap < 0 || overlap > chunkSize / 2) {
            throw new ChatApiException(400, "CHUNK_OPTIONS_INVALID", "单块长度须为 200～4000，重叠须在 0 到单块长度一半之间");
        }
    }

    private static int boundary(int[] points, int minimum, int target) {
        // 优先段落，再换行，再句末；找不到自然边界时按长度切开。
        for (int kind = 0; kind < 3; kind++) {
            for (int end = target; end >= minimum; end--) {
                int last = points[end - 1];
                boolean match = switch (kind) {
                    case 0 -> last == '\n' && end >= 2 && points[end - 2] == '\n';
                    case 1 -> last == '\n';
                    default -> "。！？!?；;".indexOf(last) >= 0
                            || (last == '.' && (end == points.length || Character.isWhitespace(points[end])));
                };
                if (match) return end;
            }
        }
        return target;
    }

    /** index 从 0 开始，原文位置采用 [startOffset, endOffset)。 */
    public record Segment(int index, int startOffset, int endOffset, String content) { }
}
