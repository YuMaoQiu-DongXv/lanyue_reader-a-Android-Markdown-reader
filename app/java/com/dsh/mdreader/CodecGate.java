package com.dsh.mdreader;

import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;

/**
 * 蓝阅 · 编码闸门：把任意字节流安全地变成一段 Java String，永不抛异常。
 *
 * <p>解码顺序（固定）：
 * <ol>
 *   <li>UTF-8 BOM (EF BB BF) → 剥掉 BOM，按 UTF-8 宽松解码（BOM 本身不算"非 UTF-8"，
 *       所以 {@code converted=false}）。</li>
 *   <li>UTF-16 BOM：FF FE → UTF-16LE，FE FF → UTF-16BE（{@code converted=true}）。</li>
 *   <li>严格 UTF-8：{@link CharsetDecoder} + {@link CodingErrorAction#REPORT}，
 *       只要有一个 MALFORMED / UNMAPPABLE 就判定"不是 UTF-8"。</li>
 *   <li>GB18030 兜底：同样用严格解码；但只有在"UTF-8 明显崩坏"时才采纳，见下。</li>
 *   <li>最后手段：UTF-8 宽松解码（坏字节变 U+FFFD），{@code converted=true}。</li>
 * </ol>
 *
 * <p>防误判（任务硬要求）：
 * <ul>
 *   <li>GB18030 解码器极其宽容，几乎能把任何字节流"成功"解成汉字。因此本类不把
 *       "GB18030 解码没报错"当成证据，而是再加两道闸：GB 结果必须**不含 NUL、不含 U+FFFD、
 *       不含异常 C0 控制符**，并且 UTF-8 宽松结果的替换字符占比 **≥ 5%**（说明整段文本
 *       都站不住脚，而不是偶发坏字节）。两条都满足才判 GB18030。</li>
 *   <li>无 BOM 的 UTF-16（纯 ASCII 内容）恰好是"合法 UTF-8"，会被第 3 步误收。因此第 3 步
 *       成功后追加一个极窄的 NUL 形态检查：NUL 占比 ≥ 25% 且集中在奇/偶位 → 改判 UTF-16。
 *       普通 UTF-8 文本不可能满足这个形态。</li>
 *   <li>文本里出现 NUL 时不会走进 GB18030 分支（NUL 直接让 GB 结果"不干净"），
 *       避免把二进制/UTF-16 文件解成整篇乱码汉字。</li>
 * </ul>
 */
public final class CodecGate {

    /** 解码结果。{@code text} 已完成 \r\n / \r → \n 归一化并剥离 BOM。 */
    public static final class Decoded {
        /** 已解码文本（行尾已归一化为 \n，BOM 已剥离）。 */
        public final String  text;
        /** "UTF-8" | "GB18030" | "UTF-16LE" | "UTF-16BE"。 */
        public final String  encoding;
        /** true = 原始文件不是 UTF-8，保存时会写成 UTF-8，UI 需要提示。 */
        public final boolean converted;

        private Decoded(String text, String encoding, boolean converted) {
            this.text = text;
            this.encoding = encoding;
            this.converted = converted;
        }
    }

    private static final String UTF8   = "UTF-8";
    private static final String GB     = "GB18030";
    private static final String U16LE  = "UTF-16LE";
    private static final String U16BE  = "UTF-16BE";

    private static final char BOM   = '\uFEFF';
    private static final char REPL  = '\uFFFD';
    private static final char NUL   = '\u0000';

    /** GB18030 判定阈值：UTF-8 宽松结果的替换字符占比达到 1/20（5%）才算"整段崩坏"。 */
    private static final int BROKEN_RATIO = 20;

    private CodecGate() {
        // 纯静态工具类
    }

    /**
     * 解码任意字节流；{@code bytes} 为 null/空时返回空文本。
     *
     * @return 非 null 的 {@link Decoded}
     */
    public static Decoded decode(byte[] bytes) {
        if (bytes == null || bytes.length == 0) {
            return new Decoded("", UTF8, false);
        }
        final int len = bytes.length;

        // ---- 1) UTF-8 BOM -------------------------------------------------
        if (len >= 3
                && (bytes[0] & 0xFF) == 0xEF
                && (bytes[1] & 0xFF) == 0xBB
                && (bytes[2] & 0xFF) == 0xBF) {
            // BOM 之后仍可能有坏字节，这里用宽松解码保证不抛错；文件本身仍是 UTF-8
            return new Decoded(normalize(new String(bytes, 3, len - 3, StandardCharsets.UTF_8)),
                    UTF8, false);
        }

        // ---- 2) UTF-16 BOM ------------------------------------------------
        if (len >= 2 && (bytes[0] & 0xFF) == 0xFF && (bytes[1] & 0xFF) == 0xFE) {
            return new Decoded(normalize(decodeUtf16(bytes, 2, len - 2, StandardCharsets.UTF_16LE)),
                    U16LE, true);
        }
        if (len >= 2 && (bytes[0] & 0xFF) == 0xFE && (bytes[1] & 0xFF) == 0xFF) {
            return new Decoded(normalize(decodeUtf16(bytes, 2, len - 2, StandardCharsets.UTF_16BE)),
                    U16BE, true);
        }

        // ---- 3) 严格 UTF-8 ------------------------------------------------
        String strictUtf8 = strict(bytes, 0, len, StandardCharsets.UTF_8);
        if (strictUtf8 != null) {
            String[] noBomU16 = guessUtf16WithoutBom(bytes);
            if (noBomU16 != null) {
                // 形态上就是 UTF-16（NUL 密集且规整），只是缺 BOM
                return new Decoded(normalize(noBomU16[0]), noBomU16[1], true);
            }
            return new Decoded(normalize(strictUtf8), UTF8, false);
        }

        // ---- 4) GB18030 兜底 ----------------------------------------------
        String loose = new String(bytes, 0, len, StandardCharsets.UTF_8);
        int repl = count(loose, REPL);
        boolean utf8ClearlyBroken = repl > 0 && (long) repl * BROKEN_RATIO >= loose.length();

        Charset gb = gb18030();
        if (gb != null) {
            String g = strict(bytes, 0, len, gb);
            if (g != null && utf8ClearlyBroken && badness(g) == 0) {
                return new Decoded(normalize(g), GB, true);
            }
        }

        // ---- 5) 最后手段：UTF-8 宽松替换 ----------------------------------
        // 注意：能走到这里说明原始字节不是合法 UTF-8 → converted = true
        return new Decoded(normalize(loose), UTF8, true);
    }

    /** 文本 → UTF-8 字节（保存统一走这里；不做 BOM）。 */
    public static byte[] utf8(String text) {
        if (text == null) {
            return new byte[0];
        }
        return text.getBytes(StandardCharsets.UTF_8);
    }

    // ------------------------------------------------------------------ 内部

    /** 行尾归一化 + 剥离可能残留在开头的 BOM。 */
    private static String normalize(String s) {
        if (s == null || s.length() == 0) {
            return "";
        }
        int start = 0;
        if (s.charAt(0) == BOM) {
            start = 1;
        }
        String body = (start == 0) ? s : s.substring(start);
        if (body.indexOf('\r') < 0) {
            return body;
        }
        return body.replace("\r\n", "\n").replace('\r', '\n');
    }

    /** 严格解码：任何非法序列返回 null（不抛异常）。 */
    private static String strict(byte[] b, int off, int len, Charset cs) {
        if (len <= 0) {
            return "";
        }
        CharsetDecoder d = cs.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT);
        try {
            return d.decode(ByteBuffer.wrap(b, off, len)).toString();
        } catch (CharacterCodingException e) {
            return null;
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** UTF-16 解码：优先严格，坏字节用宽松替换兜住（UTF-16 也不许抛）。 */
    private static String decodeUtf16(byte[] b, int off, int len, Charset cs) {
        if (len <= 0) {
            return "";
        }
        String s = strict(b, off, len, cs);
        if (s != null) {
            return s;
        }
        return new String(b, off, len, cs);
    }

    private static Charset gb18030() {
        try {
            return Charset.forName(GB);
        } catch (RuntimeException e) {
            return null; // 理论上不会发生；真发生就跳过 GB 兜底
        }
    }

    /**
     * 无 BOM 的 UTF-16 形态检查。
     *
     * @return {text, "UTF-16LE"|"UTF-16BE"}；不像 UTF-16 时返回 null
     */
    private static String[] guessUtf16WithoutBom(byte[] b) {
        final int n = b.length;
        if (n < 8 || (n & 1) != 0) {
            return null;
        }
        int nul = 0;
        int nulEven = 0;
        int nulOdd = 0;
        for (int i = 0; i < n; i++) {
            if (b[i] == 0) {
                nul++;
                if ((i & 1) == 0) {
                    nulEven++;
                } else {
                    nulOdd++;
                }
            }
        }
        if (nul * 4 < n) {
            return null; // NUL 不足 25% → 不是 UTF-16 的形态
        }
        boolean le = nulOdd * 4 >= nul * 3;  // 低字节位为 NUL → little endian
        boolean be = nulEven * 4 >= nul * 3; // 高字节位为 NUL → big endian
        Charset cs;
        String name;
        if (le && !be) {
            cs = StandardCharsets.UTF_16LE;
            name = U16LE;
        } else if (be && !le) {
            cs = StandardCharsets.UTF_16BE;
            name = U16BE;
        } else {
            return null;
        }
        String t = strict(b, 0, n, cs);
        if (t == null) {
            return null;
        }
        return new String[]{t, name};
    }

    /** 坏字符计数：U+FFFD + NUL + 非常规 C0 控制符（\t \n \r \f 不算坏）。 */
    private static int badness(String s) {
        if (s == null) {
            return 0;
        }
        int bad = 0;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == NUL || c == REPL) {
                bad++;
            } else if (c < 0x20 && c != '\t' && c != '\n' && c != '\r' && c != '\f') {
                bad++;
            }
        }
        return bad;
    }

    private static int count(String s, char c) {
        if (s == null) {
            return 0;
        }
        int n = 0;
        for (int i = 0; i < s.length(); i++) {
            if (s.charAt(i) == c) {
                n++;
            }
        }
        return n;
    }
}
