# LRC 歌词导入续接记录

更新时间：2026-10-17 21:10（用户确认剩余 34 首不在曲库）

用户要求继续后，先读本文件，再按下列状态继续。

## 最新状态：3 份获批歌词已导入并核实

- 用户已明确批准导入 3 份，并批准对 ID 622、704 覆盖歌词 URL、将 `Till I Collapse` 映射到 ID 928；The Hills (1) 保持原链接，其余 34 份继续待审核。
- 用户查看了剩余 34 首并确认自己都没有这些歌曲；这解释了它们为何在 personal 曲库中无法匹配。它们不再是需要审核映射的曲目；保持未导入、无需新建歌曲记录。若以后把歌曲加入曲库，再按实际歌曲核对歌词。
- 管理端导入 API 返回 HTTP 200、`code=0`、`data=3`。**不要重放这 3 项导入。**
- 写后只读核账：personal 数据库共 430 条非空歌词 URL、424 个唯一 URL；MinIO `jojo-music-personal/lyrics/` 共 426 个对象。数据库引用对象缺失数为 0。
- 3 个新对象及目标：
  - `Bruno Mars - Talking To The Moon (Acoustic Piano Version).lrc` → ID 622 → `lyrics/a610af55-6bd0-4524-9ab6-67c8649c5d37-991a6f5f-8a02-40f0-a5ed-4750d2364815.lrc`
  - `Eminem,Rihanna - Love the Way You Lie (Clean).lrc` → ID 704 → `lyrics/a547fcf1-9654-4121-ba57-508424368850-244e59c0-f362-466e-b057-1760300c8c18.lrc`
  - `Eminem,Nate Dogg - 'Till I Collapse.lrc` → ID 928 `Till I Collapse (NEFFEX Remix)` → `lyrics/451c8fd9-278d-47da-a236-2ab06f727d30-d800f30c-319c-4cf9-b37b-d73fb23f40d9.lrc`
- 两个旧歌词对象因获批替换而不再被数据库引用，但已按要求保留在 MinIO，**不要删除**：`lyrics/0b916ca4-a65e-4c0c-93c4-e49abdb5a8a2-d28e4da7-b89a-460f-b509-532ee9f03b2c.lrc`、`lyrics/f78faea1-dfd9-4009-ab2f-22350825488f-d4652bbe-aba4-4288-a475-f45a13faeac6.lrc`。因此这 2 个孤立对象是预期结果，并非缺失对象或误上传。
- 数据库确认 ID 622、704、928、1029 均有歌词 URL；本批只选择并导入前三项，ID 1029 未变更。
- 临时文件服务器已停止，端口 8095 已无监听；只清理了临时目录 `.lyric-version-review-20261017`（内含上述 3 份暂存副本），原始 LRC 未修改。personal 后端保持运行，未停止。
- 本批核账仅读取 personal DB 并列出 MinIO 对象；导入之外未进行额外数据库或 MinIO 写操作。

## 导入前状态（2026-10-17 18:40）

- 又导入并核实了 16 份歌词。它们都通过 LRC 文件名与 personal 曲库音频对象 basename 精确对应到唯一歌曲 ID；导入前 16 个目标的歌词字段均为空。
- 管理端导入接口返回 HTTP 200、`code=0`、`data=16`；其中 11 项从重复标题候选中按精确音频 basename 选择，另 5 项通过人工搜索选中同样精确 basename 对应的 ID。
- 导入后只读核账：personal 数据库共有 423 条歌词 URL；MinIO `jojo-music-personal/lyrics/` 有 423 个对象；423 个 URL 全部对应现存对象，缺失对象 0、孤立对象 0。
- 本批 16 个文件与目标 ID：

| LRC 文件 | 歌曲 ID |
|---|---:|
| Avril Lavigne - girlfriend (1).lrc | 560 |
| Avril Lavigne - Girlfriend.lrc | 561 |
| Bruno Mars - Catch A Grenade (The Hooligans Remix) (1).lrc | 608 |
| Bruno Mars - Catch a Grenade (The Hooligans Remix).lrc | 609 |
| Bruno Mars - Locked out of Heaven (1).lrc | 616 |
| Bruno Mars - Locked Out of Heaven.lrc | 617 |
| Michael Jackson - Heal The World (1).lrc | 894 |
| Michael Jackson - Heal the World.lrc | 895 |
| Michael Jackson - Remember The Time (1).lrc | 905 |
| Michael Jackson - Remember the Time.lrc | 906 |
| Playboi Carti,The Weeknd - RATHER LIE.lrc | 959 |
| Post Malone,The Weeknd - One Right Now.lrc | 961 |
| Pressa,Taliban Glizzy - Attachments.lrc | 963 |
| Queen,Michael Jackson - There Must Be More To Life Than This (William Orbit Mix).lrc | 965 |
| Renée Elise Goldsberry,Lin-Manuel Miranda,Phillipa Soo - It's Quiet Uptown.lrc | 967 |
| Renée Elise Goldsberry,Original Broadway Cast of Hamilton - Satisfied.lrc | 969 |

- 以下 2 份精确 basename 对应的歌曲原本已有歌词，未覆盖或重复导入：`Bruno Mars - Count On Me (1).lrc` → 611；`Carly Rae Jepsen - Call Me Maybe (1).lrc` → 634。
- 剩余 38 份应保持待审核：它们没有唯一精确的音频对象 basename 对应；不根据模糊标题自行匹配或上传。完整文件名清单见下方“38 份待审核文件清单”。只读复核中，`The Hills (1)` 对应的 ID 1029 已有歌词链接；`Love the Way You Lie (Clean)` 与 `Talking To The Moon (Acoustic Piano Version)` 的近似候选不能自动覆盖/关联。
- 只读重新比对 MinIO `songs/` 后确认 6 对歌曲音频对象 ETag 和大小一致：958/959、960/961、962/963、964/965、966/967、968/969。用户明确批准共享歌词 URL 后，在 personal 数据库中将每对已确认歌曲的歌词 URL 复制到偶数 ID；仅更新 6 行的 `lyric` 字段，不上传、修改或删除 MinIO 对象。
- 写后只读核账：数据库有 429 条歌曲歌词 URL，对应 423 个唯一对象；MinIO `lyrics/` 有 423 个对象，缺失对象 0、孤立对象 0。6 对 ID 的歌词 URL 均相同。
- 对其余 38 份重新核对标题、歌手、专辑与音频对象名；未发现新的唯一精确匹配。少数相似项是 Clean/Acoustic 等版本名不一致，或候选歌曲已经有歌词链接，均保持未导入、未覆盖。
- 本轮再次按服务端标题/艺人精确规则（包括歌手署名的 `|` 分隔）只读重算全部 463 项，确认结果与已修复匹配器一致：407 个唯一精确匹配、11 个重复歌曲歧义、45 个无标题精确匹配。
- 逐一检查 38 份未决 LRC 的 `[ar]`、`[al]`、`[ti]` 元数据头，均没有这三类标签；时间戳和文件名也不能唯一确定候选曲目/版本。未读取或修改歌词正文。
- **为何审核显示未匹配：不是 LRC 格式无效。** 38 份均通过带时间轴歌词文件校验；匹配器随后要求曲库歌曲名（忽略大小写、标点和空格）精确对应，而这 38 个文件名都找不到精确歌曲名。因此显示 `NO_MATCH`，不是因为合作歌手标点解析，也不是 MinIO 上传失败。将文件名的版本括号作为可能后缀检查后，仅有 3 个核心标题指向已有歌词的歌曲，另有 1 个只找到不同 remix；这些都不能直接导入。
- **逐项诊断：**
  - 34 份：个人曲库 `tb_song` 中没有与 LRC 标题规范化后完全相同的歌曲名称，不能在现有歌曲记录上安全挂歌词。应先确认它们是否确实对应个人曲库里的音频；若歌曲不在库中，需由用户决定是否另行新增歌曲记录。
  - 3 份版本/副本名可对应到已有歌曲，但目标已经有歌词：`Bruno Mars - Talking To The Moon (Acoustic Piano Version).lrc` → 622（音频记录为普通版且已有歌词）；`Eminem,Rihanna - Love the Way You Lie (Clean).lrc` → 704（已有歌词；Clean 版文字可能不同）；`The Weeknd - The Hills (1).lrc` → 1029（`(1)` 副本名，已有歌词）。均未覆盖或另行关联。
  - 1 份仅找到不同版本候选：`Eminem,Nate Dogg - 'Till I Collapse.lrc` 对应的近似记录是 ID 928 `Till I Collapse (NEFFEX Remix)`，不是同一版本；未导入。
  - 上述 38 个 LRC 都没有 `[ar]`、`[al]`、`[ti]` 元数据；无元数据只减少匹配线索，并非导入格式错误。
- 其余 38 份继续待用户补充具体歌曲 ID、曲库音频文件名/版本，或确认是否需要新增缺失歌曲记录。不要仅凭艺人相同或模糊标题强行选择候选，也不要覆盖现有 URL。
- 原始 LRC 未改；临时 16 文件副本、临时 HTTP 文件服务器及临时 classpath 文件已清理。后端 PID 13260 未停止。

### 38 份待审核文件清单

以下文件都位于 `D:\CloudMusic\VipSongsDownload`。本次逐一检查后确认它们都没有 `[ar]`、`[al]`、`[ti]` LRC 元数据，且尚未导入；以后续接时以此清单为准。需先确认歌曲及版本，再考虑导入。

1. `Bruno Mars - Marry You.lrc`
2. `Bruno Mars - Our First Time.lrc`
3. `Bruno Mars - Talking To The Moon (Acoustic Piano Version).lrc`
4. `Bruno Mars - Versace on the Floor.lrc`
5. `Bruno Mars,Anderson .Paak,Silk Sonic - After Last Night (with Thundercat & Bootsy Collins).lrc`
6. `Bruno Mars,Anderson .Paak,Silk Sonic - Leave The Door Open.lrc`
7. `Bruno Mars,Anderson .Paak,Silk Sonic - Love's Train.lrc`
8. `Bruno Mars,Anderson .Paak,Silk Sonic - Skate.lrc`
9. `Calvin Harris,The Weeknd - Over Now.lrc`
10. `Cardi B,Bruno Mars - Please Me.lrc`
11. `Carly Rae Jepsen - Both Sides Now.lrc`
12. `Carly Rae Jepsen - I Know You Have A Girlfriend.lrc`
13. `Carly Rae Jepsen - I Really Like You.lrc`
14. `Carly Rae Jepsen - More Than A Memory.lrc`
15. `Carly Rae Jepsen - Psychedelic Switch.lrc`
16. `Carly Rae Jepsen - Run Away With Me.lrc`
17. `Carly Rae Jepsen - This Kiss.lrc`
18. `Carly Rae Jepsen - Tiny Little Bows.lrc`
19. `Carly Rae Jepsen - Turn Me Up.lrc`
20. `Carly Rae Jepsen - Wrong Feels So Right.lrc`
21. `Doja Cat,Nicki Minaj - Say So.lrc`
22. `Doja Cat,The Weeknd - You Right.lrc`
23. `Dr. Dre,Eminem,Skylar Grey - I Need a Doctor.lrc`
24. `Drake,Kanye West,Lil Wayne - Forever.lrc`
25. `Ed Sheeran,Chris Stapleton,Bruno Mars - BLOW.lrc`
26. `Eminem - Beautiful.lrc`
27. `Eminem - Lose Yourself.lrc`
28. `Eminem - Sing For The Moment (Instrumental).lrc`
29. `Eminem - Space Bound.lrc`
30. `Eminem - Venom (Music From The Motion Picture).lrc`
31. `Eminem,Dido - Stan.lrc`
32. `Eminem,Nate Dogg - 'Till I Collapse.lrc`
33. `Eminem,Obie Trice,DMX - Go To Sleep (from the Cradle 2 The Grave soundtrack).lrc`
34. `Eminem,Rihanna - Love the Way You Lie (Clean).lrc`
35. `Eminem,Rihanna - The Monster.lrc`
36. `Eminem,Sia - Beautiful Pain.lrc`
37. `Kanye West,Charlie Wilson - Bound 2.lrc`
38. `The Weeknd - The Hills (1).lrc`

## 导入状态：已完成并核实

用户在管理端确认导入 **253 份精确匹配歌词** 后，浏览器等待 `/admin/lyrics/import` 响应超时。2026-10-17 下午已直接对 personal 数据库与 MinIO 执行只读核对：

- personal 数据库 `jojo_music_personal` 共 578 首歌曲。
- 根据本地文件名及服务端同样的标准化匹配逻辑重算，精确匹配目标为 253 首。
- 253 首目标歌曲均有歌词 URL，且全部指向 MinIO `lyrics/` 前缀。
- MinIO bucket `jojo-music-personal` 中 `lyrics/` 对象总数为 253。
- 253 个数据库 URL 全部对应现存对象；未发现缺失对象。
- `lyrics/` 下未发现未被数据库引用的孤立对象。
- 全库目前共有 253 条非空歌词 URL，全部在 `lyrics/` 前缀。

因此，不要重放这 253 份导入，不要删除或更改任何歌词对象/数据库记录。实际导入已完整成功。审计仅执行 SELECT 和 MinIO ListObjects，没有写入。

## 剩余待审核（17:45 时的历史状态；以上“当前状态”优先）

- 已修复合作歌手匹配问题并重算：463 份中现在有 407 份唯一精确匹配、11 份多歌曲记录歧义、45 份曲库无同名标题。
- 其中原先已导入的 253 份保持不动；新增 **154 份** 现在可按修复后的歌手拆分逻辑唯一匹配，当前歌词字段均为空且目标歌曲 ID 不重复。
- 当时 11 份歧义为同歌名、同歌手但曲库存在重复 song ID；本文件顶部记录了随后如何用音频对象 basename 审核处理其中 11 项。
- 当时 45 份没有标准化后的精确同名标题；只读模糊检查找到 9 个近似候选。后续其中 5 份按精确音频对象 basename 导入，2 份确认目标原本已有歌词而保持不变；其余 38 份继续待审核。
- 用户当时确认先导入 154 项；随后又要求继续处理剩余歌词。本文件顶部记录了后来获准并完成的 16 项。
- 新代码的管理端 preview 对本批 154 个文件返回 `EXACT_MATCH=154`，`existingLyric=0`，目标歌曲 ID 均不重复。
- 确认后浏览器等待接口响应超时，因此没有重试；随后 personal 数据库与 MinIO 的只读核对确认本批已完整完成。
- personal DB 全部歌词 URL 数：407；MinIO `lyrics/` 对象数：407；数据库引用的对象全部存在，孤立对象为 0。
- 154 首本批目标歌曲全部已有新歌词 URL；无空项。当时剩余 56 项尚未上传。
- 本节以下的候选表是后续 16 项导入前的审阅索引；以本文件顶部的最新状态为准，不要重放已完成批次。

### 剩余 56 项的只读候选索引

11 个精确命中但目标 ID 有重复记录：

| LRC 文件 | 候选歌曲 ID |
|---|---|
| Avril Lavigne - Girlfriend.lrc | 560 / 561 |
| Bruno Mars - Catch a Grenade (The Hooligans Remix).lrc | 608 / 609 |
| Bruno Mars - Locked Out of Heaven.lrc | 616 / 617 |
| Michael Jackson - Heal the World.lrc | 894 / 895 |
| Michael Jackson - Remember the Time.lrc | 905 / 906 |
| Playboi Carti,The Weeknd - RATHER LIE.lrc | 958 / 959 |
| Post Malone,The Weeknd - One Right Now.lrc | 960 / 961 |
| Pressa,Taliban Glizzy - Attachments.lrc | 962 / 963 |
| Queen,Michael Jackson - There Must Be More To Life Than This (William Orbit Mix).lrc | 964 / 965 |
| Renée Elise Goldsberry,Lin-Manuel Miranda,Phillipa Soo - It's Quiet Uptown.lrc | 966 / 967 |
| Renée Elise Goldsberry,Original Broadway Cast of Hamilton - Satisfied.lrc | 968 / 969 |

9 个无精确曲名、但有较近的同艺人候选；这些名字可能表示重复文件或另一版本，不可自动导入：

| LRC 文件 | 可能候选歌曲 ID / 曲名 |
|---|---|
| Avril Lavigne - girlfriend (1).lrc | 560 / girlfriend |
| Bruno Mars - Catch A Grenade (The Hooligans Remix) (1).lrc | 608 / Catch A Grenade (The Hooligans Remix) |
| Bruno Mars - Count On Me (1).lrc | 611 / Count On Me |
| Bruno Mars - Locked out of Heaven (1).lrc | 616 / Locked out of Heaven |
| Carly Rae Jepsen - Call Me Maybe (1).lrc | 634 / Call Me Maybe |
| Eminem,Rihanna - Love the Way You Lie (Clean).lrc | 704 / Love The Way You Lie |
| Michael Jackson - Heal The World (1).lrc | 894 / Heal The World |
| Michael Jackson - Remember The Time (1).lrc | 905 / Remember The Time |
| The Weeknd - The Hills (1).lrc | 1029 / The Hills |

当时其余 36 个 LRC 的标题没有足够接近的曲库候选。以上候选表为导入前的历史索引；其当前处理状态以本文件顶部记录为准。

## 本次只读歌词匹配预览

- 本地来源目录：`D:\CloudMusic\VipSongsDownload`
- 原始 LRC：463 份，合计 1,632,502 字节；无超过 1 MB 文件，无不区分大小写的重名。
- 管理端通过已登录会话调用 preview API，HTTP 200，服务端返回 463 项：
  - `EXACT_MATCH`：253
  - `TITLE_MATCH_REVIEW_ARTIST`：160
  - `AMBIGUOUS_MATCH`：5
  - `NO_MATCH`：45
- 253 个精确匹配项均自动选中；未发现精确匹配目标已有歌词；未发现重复歌曲目标。
- 其他 210 项未被选中，不得擅自替它们匹配或导入。
- 管理端最终确认框显示：“将上传 253 份歌词至 MinIO 的 lyrics/ 目录。另外 210 个文件因未匹配或未确认覆盖而跳过。”
- 用户明确选择：“只导入 253 份精确匹配项到 personal MinIO 的 lyrics/，其余 210 份保留待审。”
- 随后确认按钮被点击，但浏览器等待响应 90 秒超时，未显示成功或失败 toast。
- 后续 personal 数据库与 MinIO 的只读审计确认此次导入全量成功：253 个目标 URL、253 个歌词对象、全部一一对应，无孤立对象。

## 继续处理进度（2026-10-17 17:24）

- 查明旧匹配器先删除文件名艺人中的标点，再尝试按逗号拆分合作歌手，导致 `Bruno Mars,Anderson .Paak` 等合作艺人名无法匹配。
- 修复 `LyricImportService.artistMatches`：先从原始艺人字符串拆分各艺人，再逐项规范化，并要求每一位文件名艺人均出现在歌曲主艺人/合作艺人中。
- 增加两个回归测试：完整合作歌手可精确匹配；缺少任意一位合作歌手时仍保留待审核。
- 针对性后端测试通过：`LyricImportServiceTest` 8 项、`SongMapperLyricImportQueryTest` 1 项，共 9 项通过；`git diff --check` 通过。
- 使用 personal 数据库的只读查询重算，匹配规则与修复后服务一致：`EXACT=407`、`AMBIGUOUS=11`、`NO_TITLE=45`。
- 用户明确确认仅导入 154 项。管理端 preview 验证 154 项均精确匹配、无现有歌词、无重复目标；导入请求后浏览器超时，但只读核对确认已完整完成：154 首新增链接，DB 共 407 个 URL ↔ MinIO `lyrics/` 共 407 个对象，全部对应且无孤立对象。
- 准备上传期间只复制了 154 个 LRC 到临时目录；已清理两个临时目录、本地临时文件服务器和 classpath 文件。原始 463 个 LRC 未修改。
- 用户环境中的后端仍在 PID 13260 运行（`local,personal` profile）；本次未停止后端。

## 环境与安全约束

- 个人数据库：`jojo_music_personal`
- 个人 MinIO bucket：`jojo-music-personal`
- 歌词对象必须在独立 `lyrics/` 前缀，绝不放到 `songs/`。
- 仅更新歌曲 `tb_song.lyric` URL；保留已有音频、歌曲元数据及旧歌词对象。
- 不触碰 legacy 数据库 `jojo_music`、其他本地音乐库或无关数据。
- 保留工作区现有所有未提交改动，不 reset、不 revert、不清理无关文件。
- 不要在聊天、日志或新文档中复制 profile 密码、访问密钥等凭据。

## 已完成的实现与检查

- 后端歌词预览、人工搜索和导入 API 已接入。
- 管理端逐文件预览、人工审核及显式覆盖确认界面已接入。
- 客户端同步歌词展示已实现。
- 经审计确认，原始 463 份中已有 426 份歌词文件导入（此前 423 份加本次获批 3 份）；`Bruno Mars - Count On Me (1).lrc`、`Carly Rae Jepsen - Call Me Maybe (1).lrc` 和 `The Weeknd - The Hills (1).lrc` 对应歌曲原已有歌词，本次均未导入；仍有 34 份待审核。下方 38 项清单是本次 3 项获批前的历史名单，当前待审核项为该名单中排除本次已导入的 Acoustic Piano、Clean、`'Till I Collapse` 和保持不变的 `The Hills (1)` 后的其余 34 项。
- personal DB 当前有 430 个非空歌曲歌词字段，共引用 424 个唯一 MinIO `lyrics/` 对象；MinIO `lyrics/` 前缀有 426 个对象，缺失对象 0。2 个因用户批准替换 URL 而不再被引用的旧对象按要求保留，不要删除。6 对完全相同音频字节的重复歌曲记录已按用户批准共享歌词 URL。
- 合作艺人匹配修复后，后端定向测试 `LyricImportServiceTest` 8 项与 `SongMapperLyricImportQueryTest` 1 项均通过。管理端和客户端构建此前通过；完整 TypeScript 类型检查曾有既存错误。
- 客户端 `parsedLyrics.ts` 当前未见独立单元测试；后续可补时间戳/偏移/BOM 编码/多时间戳与歌曲切换竞态的回归测试，并在不触碰曲库数据的前提下做一次端到端歌词显示验证。
- 配置修复已把邮件设置移到 `spring.mail` 层级。
- 当前核心实现：
  - `jojo-music-server/src/main/java/cn/edu/seig/jojomusic/service/impl/LyricImportService.java`
  - `jojo-music-server/src/main/java/cn/edu/seig/jojomusic/controller/AdminController.java`
  - `jojo-music-server/src/main/java/cn/edu/seig/jojomusic/mapper/SongMapper.java`
  - `jojo-music-admin/src/views/song/form/lyric-import.vue`
  - `jojo-music-admin/src/api/system.ts`
  - `jojo-music-client/src/utils/parsedLyrics.ts`
  - `jojo-music-client/src/components/DrawerMusic/right.vue`

## 临时文件

预览过程中建立的本地临时副本已清理，原始 `D:\CloudMusic\VipSongsDownload` 文件未修改；临时文件服务器已停止。本文件记录导入与核账状态，未来续接时请先核对最新章节，不要重放已完成批次。
