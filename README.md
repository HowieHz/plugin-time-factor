# 时间因子SEO 插件

时间因子SEO 是一个专为 Halo 博客系统设计的 SEO 优化插件，通过动态注入结构化数据（meta/script 标签）来提升站点在各大搜索引擎的收录与排名表现。

## 🌐 演示与交流

- **演示站点**：[https://www.lik.cc/](https://www.lik.cc/)
- **文档**：[https://docs.lik.cc/](https://docs.lik.cc/)
- **QQ 交流群**：
    - [![QQ群](https://www.lik.cc/upload/iShot_2025-03-03_16.03.00.png)](https://www.lik.cc/upload/iShot_2025-03-03_16.03.00.png)

## 功能特性

### 结构化数据注入效果示例

下图展示了插件在页面中为不同搜索引擎动态注入的结构化数据（OG、字节、百度、Google 等）：

![结构化数据注入效果示例](https://www.lik.cc/upload/Google%20Chrome%202025-06-28%2011.51.07.png)

### 🎯 智能 SEO 注入

- **多平台支持**：支持 Google、百度、字节、OG 等主流搜索引擎的结构化数据格式
- **动态内容**：根据页面类型和内容自动填充标题、描述、作者、标签等字段

### 🔧 配置灵活

- 支持启用/禁用搜索引擎优化功能
- 可配置默认封面图片
- 自动获取站点信息（标题、Logo、关键词等）

### 🛡️ 性能优化

- 普通用户访问时零性能影响
- 结构化数据格式紧凑，无多余注释
- 异常处理完善，保证系统稳定性

## 支持注入的页面类型

### 标题与描述的取值逻辑

插件不自己拼接或硬编码任何标题/描述文字，而是直接读取主题已渲染好的 `<head>` 内容，保证 SEO 标签与页面实际展示完全一致：

**标题**：`<title>` 标签文本 → 站点标题 → 不输出

**描述**：`<meta name="description">` 的 `content` → 站点 SEO 描述 → 不输出

> 主题负责生成 `<title>` 和 `<meta name="description">`，插件只负责将它们同步到 OG、结构化数据等各类 SEO
> 标签中。若主题未设置这两项，则回退到 Halo 后台「基本设置」中的站点标题和站点描述；若站点级也为空，对应字段不输出（不生成空值标签）。

### Halo 内置页面

| 页面类型  | 模板 ID        | 额外数据来源（封面/作者/时间等）      | 文档                                                                                |
|-------|--------------|------------------------|-----------------------------------------------------------------------------------|
| 首页    | `index`      | —                      | [模板变量](https://docs.halo.run/developer-guide/theme/template-variables/index_)     |
| 文章详情页 | `post`       | Post + User + Tag      | [模板变量](https://docs.halo.run/developer-guide/theme/template-variables/post)       |
| 独立页面  | `page`       | SinglePage + User      | [模板变量](https://docs.halo.run/developer-guide/theme/template-variables/page)       |
| 分类列表页 | `categories` | —                      | [模板变量](https://docs.halo.run/developer-guide/theme/template-variables/categories) |
| 分类详情页 | `category`   | Category（封面、permalink） | [模板变量](https://docs.halo.run/developer-guide/theme/template-variables/category)   |
| 标签列表页 | `tags`       | —                      | [模板变量](https://docs.halo.run/developer-guide/theme/template-variables/tags)       |
| 归档页   | `archives`   | —                      | [模板变量](https://docs.halo.run/developer-guide/theme/template-variables/archives)   |
| 作者页   | `author`     | User（头像、permalink）     | [模板变量](https://docs.halo.run/developer-guide/theme/template-variables/author)     |

> **暂不支持**：标签详情页（`tag`）——Halo 的标签路由未注入 `_templateId` 上下文变量，插件无法识别该页面类型。

> **路由前缀**：分类列表、标签列表、归档页的 canonical URL 会读取 Halo「主题路由规则」配置（`SystemSetting.ThemeRouteRules`
> ），与站点实际路由保持同步，不使用硬编码路径。

### 第三方插件页面

| 页面类型 | 模板 ID     | 插件                                                                                |
|------|-----------|-----------------------------------------------------------------------------------|
| 瞬间列表 | `moments` | [plugin-moments](https://github.com/halo-sigs/plugin-moments)                     |
| 瞬间详情 | `moment`  | [plugin-moments](https://github.com/halo-sigs/plugin-moments)                     |
| 图库   | `photos`  | [plugin-photos](https://github.com/halo-sigs/plugin-photos)                       |
| 朋友圈  | `friends` | [plugin-friends-new](https://github.com/chengzhongxue/plugin-friends-new)         |
| 豆瓣   | `douban`  | [plugin-douban](https://github.com/chengzhongxue/plugin-douban)                   |
| 番剧   | `bangumi` | [halo-plugin-bangumi-data](https://github.com/ShiinaKin/halo-plugin-bangumi-data) |

> 第三方插件页面的标题/描述同样来自页面的 `<title>` 和 `<meta name="description">`，由对应插件的主题模板负责设置。

### 各页面输出字段差异

并非所有页面都拥有完整的 SEO 字段。当某个字段为空时，对应的 meta/script 标签会被**自动省略**（不输出空值标签），以保证结构化数据的有效性。

| 页面类型   | `og:type` | Schema.org `@type` | 发布/更新时间 | 作者 |   关键词   | 说明                         |
|--------|-----------|--------------------|:-------:|:--:|:-------:|----------------------------|
| 文章详情页  | `article` | `BlogPosting`      |    ✅    | ✅  | ✅ 文章标签  | 字段最完整的页面类型                 |
| 独立页面   | `article` | `BlogPosting`      |    ✅    | ✅  | ✅ 站点关键词 | 独立页面无标签，关键词回退到站点级          |
| 分类详情页  | `website` | `WebPage`          |    ❌    | ❌  |  ✅ 分类名  | 分类无发布时间和作者                 |
| 标签详情页  | `website` | `WebPage`          |    ❌    | ❌  |  ✅ 标签名  | （⚠️ 暂不支持）标签无发布时间和作者        |
| 作者页    | `profile` | `ProfilePage`      |    ❌    | ✅  |  ✅ 作者名  | 作者页无发布时间                   |
| 列表/聚合页 | `website` | `WebPage`          |    ❌    | ❌  | ✅ 站点关键词 | 首页、分类列表、标签列表、归档页、所有第三方插件页面 |

> **省略规则**：当发布/更新时间为空时，`og:release_date`、`og:modified_time`、`bytedance:published_time`、
`bytedance:updated_time` 不输出；百度时间因子 `<script>` 中的 `pubDate`/`upDate` 字段不输出，若两者均为空则整个百度 script
> 标签不输出；Schema.org JSON-LD 中的 `datePublished`/`dateModified` 字段不输出。当作者为空时，`og:author` 不输出。

## 支持注入的数据类型

### Link 标签

- canonical 链接
- alternate 链接

### Open Graph (OG)

- 标题、描述、封面图
- 作者、标签、发布时间
- 站点信息

### 百度结构化数据

- 文章标题、摘要、封面
- 作者、分类、发布时间
- 站点名称、Logo

### 字节跳动结构化数据

- 内容标题、描述、图片
- 作者信息、发布时间
- 站点标识

### Google JSON-LD (schema.org)

- 文章结构化数据
- 作者信息
- 发布时间（带时区）

### Twitter Cards

- 标题、描述、图片
- 站点 Twitter/X 账号信息
- 作者 Twitter/X 账号信息

## 支持的搜索引擎

- **Google** (Googlebot)
- **百度** (Baiduspider)
- **字节跳动** (Bytespider)
- **必应** (Bingbot)
- **360搜索** (360spider)
- **搜狗** (Sogou)
- **Yandex**
- **DuckDuckGo** (DuckDuckBot)
- **雅虎** (Slurp)
- **Ask** (Teoma)

## 开发环境

- Java 21+
- Node.js 18+
- pnpm

## 开发

```bash
# 构建插件
./gradlew build

# 开发前端
cd ui
pnpm install
pnpm dev
```

## 构建

```bash
./gradlew build
```

构建完成后，可以在 `build/libs` 目录找到插件 jar 文件。

## 许可证

[GPL-3.0](./LICENSE) © [Handsome](https://github.com/somehand) 