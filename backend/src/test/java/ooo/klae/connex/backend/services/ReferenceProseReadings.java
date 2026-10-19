package ooo.klae.connex.backend.services;

import java.util.List;

/** Ordinary prose must not become internal reference syntax after destination normalization. */
public final class ReferenceProseReadings {
    private ReferenceProseReadings() {
    }

    /** Returns prose and external URLs whose type/ID substrings are not Markdown destinations. */
    public static List<String> all() {
        return List.of("Note:2026/10/16 meeting with client", "Note：2026年10月16日 打ち合わせ",
                "[Update]: Deal: pending approval", "[Next]: Task: call Hanako tomorrow",
                "Status [Q3]: deal: signed", "Status [Q3]: deal: signed, invoice next",
                "[Owner]: User: Hanako", "[メモ]: Note: 来週確認", "[メモ]: Ｎｏｔｅ： 来週確認",
                "Deal:3件 closed this week", "Task：①資料作成", "(note:7)", "Call at 3pm - activity:12 logged",
                "Ship release-note:123 after reviewing note:456 in plain prose",
                "Use `]` in prose before reviewing note:123", "user:４２", "user:٤٢", "ｕｓｅｒ：４２",
                "Visit https://example.com/path]user:42 today.", "[Help](https://example.com/path]user:42)",
                "Visit https://example.com/path`user:42 today.", "[Help](https://example.com/path`user:42)",
                "Visit https://example.com/path|user:42 today.", "[Help](https://example.com/path|user:42)");
    }
}
