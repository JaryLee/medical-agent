# ADR-0006：受控 DOCX 模板协议

- 状态：已接受
- 日期：2026-07-26
- 最近更新：2026-07-29

## 决策

采用 `controlled-docx-placeholders/v2`。继续支持 15 个既有文本白名单占位符，并增加以下
受控结构指令：

- `${project.logo}`：必须独占段落；缺少 Logo 数据时清空。仅允许不超过 1MB 的 PNG/JPEG，
  宽高均不得超过 2000 像素，写入时在 480×160 像素边界内等比缩放。
- `${list.research.objectives}`、`${list.research.inclusionCriteria}`、
  `${list.research.exclusionCriteria}`、`${list.research.outcomes}` 和
  `${list.research.variables}`：必须独占段落，按换行拆分为 Word 原生项目符号段落。
- `${repeat.ref.index}` 与 `${repeat.ref.text}`：必须各自独占段落并同时出现在同一表格行；
  渲染时按已核验参考文献复制该行并生成从 1 开始的序号。

结构化指令不能与任意文字混排，引用重复指令不能离开表格行。模板须经过包安全检查、字段与
结构位置校验、匿名试生成、版本化和发布后才能正式使用。旧文本占位模板保持兼容。

## 非目标

不承诺宏、复杂 Word 域、任意集合/嵌套循环、任意文本框、SVG Logo 或像素级兼容。
