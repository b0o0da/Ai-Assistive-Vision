# Screen snapshot golden fixtures (WS17-05)

Each file has a `snapshot` (the `ScreenSnapshot` contract shape, `contracts/schemas`) and the
`expected` lines `TextNormalizer` must produce from it, in order. `TextNormalizerGoldenTest`
replays all of them.

| File | What it checks |
|---|---|
| `whatsapp_chat_list.json` | icon labels dropped, row descriptions not repeated by their children, unread badge, emoji words, Arabic kept |
| `whatsapp_chat_thread.json` | back icon dropped, read ticks kept, the same short reply sent twice is kept twice |
| `facebook_feed_post.json` | "3h" read as "3 hours ago", lone separator dropped, repeated emoji said once |
| `settings_list.json` | row and switch duplicates, invisible bidi marks, password field never read |
| `banking_blocked.json` | deny-listed app: nothing read |

All texts are invented (mock screens, `datasets/LICENSES.md` row "Mock screens"). No real user content.
