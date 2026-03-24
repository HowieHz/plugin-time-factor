package cc.lik.timefactor.process;

import cc.lik.timefactor.service.SettingConfigGetter;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Arrays;
import java.util.Optional;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Component;
import org.thymeleaf.context.ITemplateContext;
import org.thymeleaf.model.IModel;
import org.thymeleaf.model.IModelFactory;
import org.thymeleaf.processor.element.IElementModelStructureHandler;
import org.unbescape.html.HtmlEscape;
import org.unbescape.json.JsonEscape;
import reactor.core.publisher.Mono;
import run.halo.app.core.extension.User;
import run.halo.app.core.extension.content.Category;
import run.halo.app.core.extension.content.Post;
import run.halo.app.core.extension.content.SinglePage;
import run.halo.app.core.extension.content.Tag;
import run.halo.app.extension.ListOptions;
import run.halo.app.extension.ReactiveExtensionClient;
import run.halo.app.extension.index.query.Queries;
import run.halo.app.extension.router.selector.FieldSelector;
import run.halo.app.infra.ExternalLinkProcessor;
import run.halo.app.infra.SystemInfo;
import run.halo.app.infra.SystemInfoGetter;
import run.halo.app.theme.dialect.TemplateHeadProcessor;
import run.halo.app.theme.router.ModelConst;

/**
 * 模板 ID 枚举，映射 Halo 路由系统中的 {@code _templateId} 到具体页面类型。
 *
 * <p>参考 Halo 内置模板枚举：
 * <a href="https://github.com/halo-dev/halo/blob/main/application/src/main/java/run/halo/app/theme/DefaultTemplateEnum.java">
 * DefaultTemplateEnum.java</a>
 */
@Getter
enum TemplateEnum {
    // ---- 内置模板 ID ----
    // 参考 https://github.com/halo-dev/halo/blob/main/application/src/main/java/run/halo/app/theme/DefaultTemplateEnum.java
    INDEX("index"), CATEGORIES("categories"), CATEGORY("category"), ARCHIVES("archives"),
    POST("post"), TAG("tag"), TAGS("tags"), SINGLE_PAGE("page"), AUTHOR("author"),

    // ---- 第三方插件适配 ----
    // 适配瞬间插件 https://github.com/halo-sigs/plugin-moments
    MOMENTS("moments"),  // 瞬间列表，路径 /moments
    MOMENT("moment"),    // 瞬间详情页，路径 /moments/{slug}
    // 适配图库管理插件 https://github.com/halo-sigs/plugin-photos
    PHOTOS("photos"),    // 路径 /photos
    // 适配朋友圈插件 https://github.com/chengzhongxue/plugin-friends-new
    FRIENDS("friends"),  // 路径 /friends
    // 适配豆瓣插件 https://github.com/chengzhongxue/plugin-douban
    DOUBAN("douban"),    // 路径 /douban
    // 适配 BangumiData 插件 https://github.com/ShiinaKin/halo-plugin-bangumi-data
    BANGUMI("bangumi"),  // 路径 /bangumi

    // ---- 无法适配的插件（_templateId 为 null，无法识别） ----
    // 足迹插件 https://github.com/acanyo/halo-plugin-footprint
    // 链接管理插件 https://github.com/halo-sigs/plugin-links
    // 追番插件 https://github.com/Roozenlz/plugin-bilibili-bangumi

    // 未知模板
    UNKNOWN("unknown");

    private final String value;

    TemplateEnum(String value) {
        this.value = value;
    }

    static TemplateEnum fromTemplateId(String templateId) {
        return Arrays.stream(values()).filter(item -> item.getValue().equals(templateId))
            .findFirst().orElse(UNKNOWN);
    }
}

/**
 * 时间因子 SEO 处理器，为 Halo 主题页面的 {@code <head>} 注入结构化 SEO 数据。
 *
 * <p>核心设计思路：
 * <ol>
 *   <li><b>模板分发</b>：根据 {@code _templateId} 上下文变量分发到对应页面处理器</li>
 *   <li><b>官方详情页使用强类型</b>：POST、SINGLE_PAGE、CATEGORY、TAG、AUTHOR 通过
 *       {@link ReactiveExtensionClient} 获取 Halo Extension 对象（Post、SinglePage、
 *       Category、Tag、User），完全避免反射，保证类型安全和编译期检查</li>
 *   <li><b>列表页使用站点信息</b>：INDEX、CATEGORIES、TAGS、ARCHIVES 等列表页没有
 *       特定实体对象，使用站点级信息（标题、描述、Logo）构建 SEO 数据</li>
 *   <li><b>第三方插件降级处理</b>：由于第三方插件的 Vo 类型不在编译期 classpath 中，
 *       第三方插件页面（MOMENTS、PHOTOS、FRIENDS、DOUBAN、BANGUMI）统一使用
 *       站点级信息 + 页面语义标题构建，部分可利用的上下文变量（如豆瓣的 title）也会被读取</li>
 *   <li><b>统一输出管道</b>：所有页面最终通过 {@link #generateSeoTags} 输出，
 *       保证标签格式一致，配置项统一生效</li>
 * </ol>
 *
 * @see TemplateHeadProcessor
 * @see <a href="https://docs.halo.run/developer-guide/theme/template-variables">Halo 模板变量文档</a>
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class TimeFactorProcess implements TemplateHeadProcessor {

    /**
     * 百度时间因子格式：不含时区偏移，如 {@code 2024-01-15T10:30:00}
     */
    private static final DateTimeFormatter BAIDU_FORMATTER =
        DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss");

    /**
     * Google/Schema.org 时间格式：含时区偏移，如 {@code 2024-01-15T10:30:00+08:00}
     */
    private static final DateTimeFormatter GOOGLE_FORMATTER =
        DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ssXXX");

    private final ReactiveExtensionClient client;
    private final SettingConfigGetter settingConfigGetter;
    private final ExternalLinkProcessor externalLinkProcessor;
    private final SystemInfoGetter systemInfoGetter;

    // ======================== 入口方法 ========================

    /**
     * Halo 模板头处理器入口，根据模板类型分发到对应的 SEO 处理器。
     *
     * <p>从 Thymeleaf 上下文读取 {@code _templateId} 变量（由 Halo 路由系统注入），
     * 映射为 {@link TemplateEnum} 后分发到对应处理方法。未知模板返回空 Mono，
     * 不影响页面渲染。全局 onErrorResume 兜底，确保 SEO 注入失败不会影响页面加载。
     *
     * @param context Thymeleaf 模板上下文，包含路由注入的模板变量
     * @param model 待输出的 HTML 模型，SEO 标签将追加到此模型
     * @param handler 结构处理器（本插件未使用）
     */
    @Override
    public Mono<Void> process(ITemplateContext context, IModel model,
        IElementModelStructureHandler handler) {
        var templateId =
            Optional.ofNullable(context.getVariable(ModelConst.TEMPLATE_ID)).map(Object::toString)
                .orElse(null);
        var template = TemplateEnum.fromTemplateId(templateId);
        log.debug("Processing SEO for templateId: {}", templateId);

        // 按模板类型分发，全局捕获异常避免影响页面渲染
        Mono<Void> result = switch (template) {
            // ---- 官方页面 ----
            case INDEX -> processIndexSeoData(context, model);
            case POST -> processPostSeoData(context, model);
            case SINGLE_PAGE -> processSinglePageSeoData(context, model);
            case CATEGORIES -> processCategoriesSeoData(context, model);
            case CATEGORY -> processCategorySeoData(context, model);
            case ARCHIVES -> processArchivesSeoData(context, model);
            case TAGS -> processTagsSeoData(context, model);
            case TAG -> processTagSeoData(context, model);
            case AUTHOR -> processAuthorSeoData(context, model);
            // ---- 第三方插件页面 ----
            case MOMENTS -> processMomentsSeoData(context, model);
            case MOMENT -> processMomentSeoData(context, model);
            case PHOTOS -> processPhotosSeoData(context, model);
            case FRIENDS -> processFriendsSeoData(context, model);
            case DOUBAN -> processDoubanSeoData(context, model);
            case BANGUMI -> processBangumiSeoData(context, model);
            case UNKNOWN -> Mono.empty();
        };

        return result.onErrorResume(error -> {
            log.warn("Failed to process SEO for templateId: {}", templateId, error);
            return Mono.empty();
        });
    }

    // ======================== 列表页 SEO 处理器 ========================
    // 设计思路：列表页没有具体的内容实体对象，使用站点级信息 + 页面语义标题构建 SEO 数据。
    // 所有列表页共享 buildListPageSeoData 方法，仅传入不同的标题、描述和路径。

    /**
     * 首页 SEO 注入。
     *
     * <p>首页是站点的入口页面，使用站点标题和 SEO 描述构建。
     * 模板变量：{@code posts} (UrlContextListResult&lt;ListedPostVo&gt;)
     *
     * @see
     * <a href="https://docs.halo.run/developer-guide/theme/template-variables/index_">首页模板变量</a>
     */
    private Mono<Void> processIndexSeoData(ITemplateContext context, IModel model) {
        return buildListPageSeoData(context, model, "首页", "", "/");
    }

    /**
     * 分类列表页 SEO 注入。
     *
     * <p>展示所有分类的导航页面。
     * 模板变量：{@code categories} (List&lt;CategoryTreeVo&gt;)
     *
     * @see
     * <a href="https://docs.halo.run/developer-guide/theme/template-variables/categories">分类列表模板变量</a>
     */
    private Mono<Void> processCategoriesSeoData(ITemplateContext context, IModel model) {
        return buildListPageSeoData(context, model, "分类", "分类导航页面", "/categories");
    }

    /**
     * 归档页 SEO 注入。
     *
     * <p>按时间归档的文章列表页面。
     * 模板变量：{@code archives} (UrlContextListResult&lt;PostArchiveVo&gt;)
     *
     * @see
     * <a href="https://docs.halo.run/developer-guide/theme/template-variables/archives">归档模板变量</a>
     */
    private Mono<Void> processArchivesSeoData(ITemplateContext context, IModel model) {
        return buildListPageSeoData(context, model, "归档", "内容归档页面", "/archives");
    }

    /**
     * 标签列表页 SEO 注入。
     *
     * <p>展示所有标签的导航页面。
     * 模板变量：{@code tags} (List&lt;TagVo&gt;)
     *
     * @see
     * <a href="https://docs.halo.run/developer-guide/theme/template-variables/tags">标签列表模板变量</a>
     */
    private Mono<Void> processTagsSeoData(ITemplateContext context, IModel model) {
        return buildListPageSeoData(context, model, "标签", "标签导航页面", "/tags");
    }

    /**
     * 瞬间列表页 SEO 注入（第三方插件 plugin-moments）。
     *
     * <p>瞬间插件的列表页面，使用站点级信息构建。
     * MomentVo 结构：spec.content.html, spec.releaseTime, spec.owner, owner.displayName
     * 由于 MomentVo 不在编译期 classpath，此处使用站点级信息降级处理。
     *
     * @see <a href="https://github.com/halo-sigs/plugin-moments">plugin-moments</a>
     */
    private Mono<Void> processMomentsSeoData(ITemplateContext context, IModel model) {
        return buildListPageSeoData(context, model, "瞬间", "瞬间列表页面", "/moments");
    }

    /**
     * 瞬间详情页 SEO 注入（第三方插件 plugin-moments）。
     *
     * <p>由于 MomentVo 类型不在编译期 classpath 中，无法强类型读取。
     * 瞬间详情页通常为短内容社交帖子，站点级 SEO 已满足基本需求。
     * 如需更精确的 SEO 数据，可在未来版本通过 Finder API 增强。
     */
    private Mono<Void> processMomentSeoData(ITemplateContext context, IModel model) {
        return buildListPageSeoData(context, model, "瞬间", "瞬间详情页面", "/moments");
    }

    /**
     * 图库页 SEO 注入（第三方插件 plugin-photos）。
     *
     * <p>图库插件的列表页面。PhotoVo 结构：spec.displayName, spec.description,
     * spec.url, spec.cover。由于不在 classpath，使用站点级信息降级。
     *
     * @see <a href="https://github.com/halo-sigs/plugin-photos">plugin-photos</a>
     */
    private Mono<Void> processPhotosSeoData(ITemplateContext context, IModel model) {
        return buildListPageSeoData(context, model, "图库", "图库页面", "/photos");
    }

    /**
     * 朋友圈页 SEO 注入（第三方插件 plugin-friends-new）。
     *
     * <p>朋友圈插件的列表页面。FriendPostVo 结构：spec.author, spec.logo,
     * spec.title, spec.postLink, spec.description, spec.pubDate。
     * 由于不在 classpath，使用站点级信息降级。
     *
     * @see <a href="https://github.com/chengzhongxue/plugin-friends-new">plugin-friends-new</a>
     */
    private Mono<Void> processFriendsSeoData(ITemplateContext context, IModel model) {
        return buildListPageSeoData(context, model, "朋友圈", "朋友圈页面", "/friends");
    }

    /**
     * 豆瓣页 SEO 注入（第三方插件 plugin-douban）。
     *
     * <p>豆瓣插件路由在上下文中设置了 {@code title} 变量
     * （由 {@code DoubanRouter.getDoubanTitle()} 生成），优先使用该变量作为页面标题。
     * DoubanMovieVo 结构：spec.name, spec.poster, spec.link, spec.score, spec.year,
     * faves.createTime, faves.remark。
     *
     * @see <a href="https://github.com/chengzhongxue/plugin-douban">plugin-douban</a>
     */
    private Mono<Void> processDoubanSeoData(ITemplateContext context, IModel model) {
        // 豆瓣路由设置了 title 上下文变量（DoubanRouter.getDoubanTitle），优先使用
        var doubanTitle = Optional.ofNullable(context.getVariable("title")).map(Object::toString)
            .filter(s -> !s.isBlank()).orElse("豆瓣");
        return buildListPageSeoData(context, model, doubanTitle, "豆瓣内容页面", "/douban");
    }

    /**
     * 番剧页 SEO 注入（第三方插件 halo-plugin-bangumi-data）。
     *
     * <p>番剧插件通过 {@code bangumiDataFinder} 提供数据。
     * BangumiUserData（Kotlin 数据类）结构：spec.nickname, spec.avatar, spec.sign,
     * spec.*CollectionJson（各类收藏的 JSON 字符串）。
     * 由于数据模型为 Kotlin 类且不在 classpath，使用站点级信息降级。
     *
     * @see
     * <a href="https://github.com/ShiinaKin/halo-plugin-bangumi-data">halo-plugin-bangumi-data</a>
     */
    private Mono<Void> processBangumiSeoData(ITemplateContext context, IModel model) {
        return buildListPageSeoData(context, model, "番剧", "番剧数据页面", "/bangumi");
    }

    // ======================== 详情页 SEO 处理器 ========================
    // 设计思路：详情页有具体的内容实体，通过上下文变量 name（Halo 路由系统注入的
    // metadata.name）获取实体标识，再用 ReactiveExtensionClient 获取强类型 Extension
    // 对象，完全避免反射。这与文章详情页（POST）的实现方式一致。

    /**
     * 文章详情页 SEO 注入。
     *
     * <p>通过上下文变量 {@code name}（文章的 metadata.name）获取强类型
     * {@link Post} 对象，提取标题、摘要、封面、作者、标签、发布/更新时间等完整 SEO 信息。
     * 这是插件最核心的 SEO 注入场景，字段精度最高。
     *
     * <p>模板变量：{@code post} (PostVo), {@code name} (String)
     * <p>数据来源：Post.spec.title, Post.status.excerpt, Post.spec.cover,
     * Post.status.permalink, Post.spec.owner → User.spec.displayName,
     * Post.spec.tags → Tag.spec.displayName, Post.spec.publishTime,
     * Post.status.lastModifyTime
     *
     * @see <a href="https://docs.halo.run/developer-guide/theme/template-variables/post">文章模板变量</a>
     */
    private Mono<Void> processPostSeoData(ITemplateContext context, IModel model) {
        var modelFactory = context.getModelFactory();
        // Halo 路由在上下文中设置 name = post.metadata.name
        var postName = Optional.ofNullable(context.getVariable("name")).map(Object::toString)
            .filter(name -> !name.isEmpty()).orElse(null);
        if (postName == null) {
            return Mono.empty();
        }
        return client.fetch(Post.class, postName).flatMap(post -> buildSeoDataForPost(post).flatMap(
            seoData -> generateSeoTags(seoData, model, modelFactory)));
    }

    /**
     * 分类详情页 SEO 注入。
     *
     * <p>通过上下文变量 {@code name}（分类的 metadata.name）获取强类型
     * {@link Category} 对象。分类拥有 displayName、description、cover 等字段，
     * 但没有独立的作者和发布时间。
     *
     * <p>模板变量：{@code category} (CategoryVo), {@code posts} (UrlContextListResult),
     * {@code name} (String)
     * <p>数据来源：Category.spec.displayName, Category.spec.description,
     * Category.spec.cover, Category.status.permalink
     *
     * @see
     * <a href="https://docs.halo.run/developer-guide/theme/template-variables/category">分类详情模板变量</a>
     */
    private Mono<Void> processCategorySeoData(ITemplateContext context, IModel model) {
        var modelFactory = context.getModelFactory();
        var categoryName = Optional.ofNullable(context.getVariable("name")).map(Object::toString)
            .filter(s -> !s.isEmpty()).orElse(null);
        if (categoryName == null) {
            return Mono.empty();
        }
        return client.fetch(Category.class, categoryName).flatMap(
            category -> buildSeoDataForCategory(category).flatMap(
                seoData -> generateSeoTags(seoData, model, modelFactory)));
    }

    /**
     * 标签详情页 SEO 注入。
     *
     * <p>通过上下文变量 {@code name}（标签的 metadata.name）获取强类型
     * {@link Tag} 对象。注意：Tag 没有 description 字段，使用
     * "标签: displayName" 作为描述。
     *
     * <p>模板变量：{@code tag} (TagVo), {@code posts} (UrlContextListResult),
     * {@code name} (String)
     * <p>数据来源：Tag.spec.displayName, Tag.spec.cover, Tag.status.permalink
     *
     * @see
     * <a href="https://docs.halo.run/developer-guide/theme/template-variables/tag">标签详情模板变量</a>
     */
    private Mono<Void> processTagSeoData(ITemplateContext context, IModel model) {
        var modelFactory = context.getModelFactory();
        var tagName = Optional.ofNullable(context.getVariable("name")).map(Object::toString)
            .filter(s -> !s.isEmpty()).orElse(null);
        if (tagName == null) {
            return Mono.empty();
        }
        return client.fetch(Tag.class, tagName).flatMap(tag -> buildSeoDataForTag(tag).flatMap(
            seoData -> generateSeoTags(seoData, model, modelFactory)));
    }

    /**
     * 独立页面 SEO 注入。
     *
     * <p>独立页面与文章结构类似，拥有标题、摘要、封面、发布时间等字段，
     * 通过 {@code name} 上下文变量获取 {@link SinglePage} 对象。
     * 与文章的区别：独立页面没有分类和标签，关键词使用站点级 SEO 关键词。
     *
     * <p>模板变量：{@code singlePage} (SinglePageVo), {@code name} (String)
     * <p>数据来源：SinglePage.spec.title, SinglePage.spec.cover,
     * SinglePage.spec.publishTime, SinglePage.spec.owner → User.spec.displayName,
     * SinglePage.status.permalink, SinglePage.status.excerpt,
     * SinglePage.status.lastModifyTime（SinglePageStatus 继承 PostStatus）
     *
     * @see
     * <a href="https://docs.halo.run/developer-guide/theme/template-variables/page">独立页面模板变量</a>
     */
    private Mono<Void> processSinglePageSeoData(ITemplateContext context, IModel model) {
        var modelFactory = context.getModelFactory();
        var pageName = Optional.ofNullable(context.getVariable("name")).map(Object::toString)
            .filter(s -> !s.isEmpty()).orElse(null);
        if (pageName == null) {
            return Mono.empty();
        }
        return client.fetch(SinglePage.class, pageName).flatMap(
            page -> buildSeoDataForSinglePage(page).flatMap(
                seoData -> generateSeoTags(seoData, model, modelFactory)));
    }

    /**
     * 作者页 SEO 注入。
     *
     * <p>通过上下文变量 {@code name}（用户的 metadata.name）获取强类型
     * {@link User} 对象。注意：User 没有 status.permalink 字段，
     * URL 通过 "/authors/" + metadata.name 手动构造。
     *
     * <p>模板变量：{@code author} (UserVo), {@code posts} (UrlContextListResult),
     * {@code name} (String)
     * <p>数据来源：User.spec.displayName, User.spec.bio, User.spec.avatar
     *
     * @see
     * <a href="https://docs.halo.run/developer-guide/theme/template-variables/author">作者模板变量</a>
     */
    private Mono<Void> processAuthorSeoData(ITemplateContext context, IModel model) {
        var modelFactory = context.getModelFactory();
        var userName = Optional.ofNullable(context.getVariable("name")).map(Object::toString)
            .filter(s -> !s.isEmpty()).orElse(null);
        if (userName == null) {
            return Mono.empty();
        }
        return client.fetch(User.class, userName).flatMap(
            user -> buildSeoDataForAuthor(user).flatMap(
                seoData -> generateSeoTags(seoData, model, modelFactory)));
    }

    // ======================== SEO 数据构建方法 ========================

    /**
     * 列表页通用 SEO 数据构建。
     *
     * <p>设计思路：列表页（首页、分类列表、标签列表、归档页、第三方插件列表页）
     * 没有具体的内容实体对象，使用站点级信息构建 SEO 数据。
     * <ul>
     *   <li>标题格式："pageTitle - siteName"（站点名称为空时仅 pageTitle）</li>
     *   <li>描述优先级：传入描述 &gt; 站点 SEO 描述 &gt; 页面标题</li>
     *   <li>封面：使用插件设置的默认封面，回退到站点 Logo</li>
     *   <li>URL：通过 {@link ExternalLinkProcessor} 将路径转为完整外部链接</li>
     *   <li>作者/日期：列表页不包含具体的作者和时间信息，字段留空</li>
     * </ul>
     *
     * @param context Thymeleaf 模板上下文
     * @param model HTML 输出模型
     * @param pageTitle 页面语义标题，如 "首页"、"分类"、"标签"
     * @param fallbackDesc 页面描述，为空时回退到站点 SEO 描述
     * @param pagePath 页面路径，如 "/"、"/categories"、"/tags"
     */
    private Mono<Void> buildListPageSeoData(ITemplateContext context, IModel model,
        String pageTitle, String fallbackDesc, String pagePath) {
        var modelFactory = context.getModelFactory();

        return Mono.zip(settingConfigGetter.getBasicConfig(), systemInfoGetter.get())
            .flatMap(tuple -> {
                var config = tuple.getT1();
                var systemInfo = tuple.getT2();

                var siteName = safeString(systemInfo.getTitle());
                var siteLogo = externalLinkProcessor.processLink(safeString(systemInfo.getLogo()));

                // 标题格式：pageTitle - siteName
                var title = siteName.isBlank() ? pageTitle : pageTitle + " - " + siteName;

                // 描述优先级：传入描述 > 站点 SEO 描述 > 页面标题
                var siteDesc =
                    Optional.ofNullable(systemInfo.getSeo()).map(SystemInfo.SeoProp::getDescription)
                        .orElse("");
                var description = firstNonBlank(fallbackDesc, siteDesc, pageTitle);

                // 通过 ExternalLinkProcessor 将相对路径转为完整的外部 URL
                var pageUrl = externalLinkProcessor.processLink(pagePath);
                var coverUrl = externalLinkProcessor.processLink(
                    firstNonBlank(config.getDefaultImage(), systemInfo.getLogo()));
                var keywords =
                    Optional.ofNullable(systemInfo.getSeo()).map(SystemInfo.SeoProp::getKeywords)
                        .orElse("");

                // 列表页没有具体的发布/更新时间和作者信息，相关字段留空
                var seoData =
                    new SeoData(title, description, coverUrl, pageUrl, siteName, "", "", "", "",
                        siteName, siteLogo, keywords);

                return generateSeoTags(seoData, model, modelFactory);
            }).onErrorResume(error -> {
                log.warn("Failed to build list page SEO for: {}", pageTitle, error);
                return Mono.empty();
            });
    }

    /**
     * 构建文章详情页 SEO 数据。
     *
     * <p>从 {@link Post} 强类型对象提取完整的 SEO 信息。
     * 并行获取四个数据源：文章所有者（User）、文章标签（Tag）、插件配置、系统信息。
     *
     * <p>字段映射：
     * <ul>
     *   <li>标题 ← {@code post.spec.title}</li>
     *   <li>摘要 ← {@code post.status.excerpt}</li>
     *   <li>封面 ← {@code post.spec.cover}，回退到插件默认封面</li>
     *   <li>URL ← {@code post.status.permalink}</li>
     *   <li>作者 ← 通过 {@code post.spec.owner} 查询 User 获取 displayName</li>
     *   <li>关键词 ← 通过 {@code post.spec.tags} 查询 Tag 获取 displayName，
     *       回退到站点 SEO 关键词</li>
     *   <li>发布时间 ← {@code post.spec.publishTime}</li>
     *   <li>更新时间 ← {@code post.status.lastModifyTime}</li>
     * </ul>
     *
     * @param post 文章 Extension 对象
     * @return 包含完整 SEO 数据的 Mono
     */
    private Mono<SeoData> buildSeoDataForPost(Post post) {
        // 并行获取：文章所有者、文章标签、插件配置、系统信息
        return Mono.zip(client.fetch(User.class, post.getSpec().getOwner()), findTagForPost(post),
            settingConfigGetter.getBasicConfig(), systemInfoGetter.get()).map(tuple -> {
            var user = tuple.getT1();
            var keywords = tuple.getT2();
            var config = tuple.getT3();
            var systemInfo = tuple.getT4();

            // 作者名：优先 User.spec.displayName，回退到 owner 标识符
            var author = Optional.of(user).map(User::getSpec).map(User.UserSpec::getDisplayName)
                .orElse(post.getSpec().getOwner());

            var postUrl = externalLinkProcessor.processLink(post.getStatus().getPermalink());
            var title = post.getSpec().getTitle();
            var description = post.getStatus().getExcerpt();
            // 封面：优先文章封面，回退到插件默认封面
            var coverUrl = externalLinkProcessor.processLink(
                Optional.ofNullable(post.getSpec().getCover()).filter(cover -> !cover.isBlank())
                    .orElse(config.getDefaultImage()));

            var publishInstant = post.getSpec().getPublishTime();
            var updateInstant = post.getStatus().getLastModifyTime();
            var zoneId = ZoneId.systemDefault();

            var siteName = systemInfo.getTitle();
            var siteLogo = externalLinkProcessor.processLink(systemInfo.getLogo());
            var siteKeywords =
                Optional.ofNullable(systemInfo.getSeo()).map(SystemInfo.SeoProp::getKeywords)
                    .orElse("");
            // 关键词：优先文章标签，回退到站点 SEO 关键词
            var finalKeywords = keywords.isBlank() ? siteKeywords : keywords;

            return new SeoData(title, description, coverUrl, postUrl, author,
                formatDateTime(publishInstant, BAIDU_FORMATTER, zoneId),
                formatDateTime(updateInstant, BAIDU_FORMATTER, zoneId),
                formatDateTime(publishInstant, GOOGLE_FORMATTER, zoneId),
                formatDateTime(updateInstant, GOOGLE_FORMATTER, zoneId), siteName, siteLogo,
                finalKeywords);
        });
    }

    /**
     * 构建独立页面 SEO 数据。
     *
     * <p>结构类似文章，从 {@link SinglePage} 提取 SEO 信息。
     * 与文章的区别：独立页面没有分类和标签，关键词使用站点级 SEO 关键词。
     * SinglePageStatus 继承 PostStatus，共享 getPermalink、getExcerpt、
     * getLastModifyTime 方法。
     *
     * <p>字段映射：
     * <ul>
     *   <li>标题 ← {@code singlePage.spec.title}</li>
     *   <li>摘要 ← {@code singlePage.status.excerpt}</li>
     *   <li>封面 ← {@code singlePage.spec.cover}，回退到插件默认封面</li>
     *   <li>URL ← {@code singlePage.status.permalink}</li>
     *   <li>作者 ← 通过 {@code singlePage.spec.owner} 查询 User</li>
     *   <li>日期 ← {@code spec.publishTime}, {@code status.lastModifyTime}</li>
     *   <li>关键词 ← 站点 SEO 关键词（独立页面无标签）</li>
     * </ul>
     *
     * @param page 独立页面 Extension 对象
     * @return 包含 SEO 数据的 Mono
     */
    private Mono<SeoData> buildSeoDataForSinglePage(SinglePage page) {
        // 并行获取：页面所有者、插件配置、系统信息
        return Mono.zip(client.fetch(User.class, page.getSpec().getOwner()),
            settingConfigGetter.getBasicConfig(), systemInfoGetter.get()).map(tuple -> {
            var user = tuple.getT1();
            var config = tuple.getT2();
            var systemInfo = tuple.getT3();

            var author = Optional.of(user).map(User::getSpec).map(User.UserSpec::getDisplayName)
                .orElse(page.getSpec().getOwner());

            var pageUrl = externalLinkProcessor.processLink(page.getStatus().getPermalink());
            var title = page.getSpec().getTitle();
            var description = page.getStatus().getExcerpt();
            var coverUrl = externalLinkProcessor.processLink(
                Optional.ofNullable(page.getSpec().getCover()).filter(cover -> !cover.isBlank())
                    .orElse(config.getDefaultImage()));

            var publishInstant = page.getSpec().getPublishTime();
            var updateInstant = page.getStatus().getLastModifyTime();
            var zoneId = ZoneId.systemDefault();

            var siteName = systemInfo.getTitle();
            var siteLogo = externalLinkProcessor.processLink(systemInfo.getLogo());
            var keywords =
                Optional.ofNullable(systemInfo.getSeo()).map(SystemInfo.SeoProp::getKeywords)
                    .orElse("");

            return new SeoData(title, description, coverUrl, pageUrl, author,
                formatDateTime(publishInstant, BAIDU_FORMATTER, zoneId),
                formatDateTime(updateInstant, BAIDU_FORMATTER, zoneId),
                formatDateTime(publishInstant, GOOGLE_FORMATTER, zoneId),
                formatDateTime(updateInstant, GOOGLE_FORMATTER, zoneId), siteName, siteLogo,
                keywords);
        });
    }

    /**
     * 构建分类详情页 SEO 数据。
     *
     * <p>从 {@link Category} 提取分类级 SEO 信息。分类没有独立的作者和发布时间，
     * 作者字段使用站点名称，日期字段留空。
     *
     * <p>字段映射：
     * <ul>
     *   <li>标题 ← {@code category.spec.displayName} + " - " + 站点名称</li>
     *   <li>描述 ← {@code category.spec.description}，回退到 "分类: displayName"</li>
     *   <li>封面 ← {@code category.spec.cover}，回退到默认封面/站点 Logo</li>
     *   <li>URL ← {@code category.status.permalink}</li>
     *   <li>关键词 ← 分类显示名</li>
     * </ul>
     *
     * @param category 分类 Extension 对象
     * @return 包含 SEO 数据的 Mono
     */
    private Mono<SeoData> buildSeoDataForCategory(Category category) {
        return Mono.zip(settingConfigGetter.getBasicConfig(), systemInfoGetter.get()).map(tuple -> {
            var config = tuple.getT1();
            var systemInfo = tuple.getT2();

            var displayName = safeString(category.getSpec().getDisplayName());
            var siteName = safeString(systemInfo.getTitle());
            var title = siteName.isBlank() ? displayName : displayName + " - " + siteName;

            // 分类有 description 字段，回退到 "分类: displayName"
            var description =
                firstNonBlank(category.getSpec().getDescription(), "分类: " + displayName);

            var pageUrl = externalLinkProcessor.processLink(category.getStatus().getPermalink());
            var coverUrl = externalLinkProcessor.processLink(
                firstNonBlank(category.getSpec().getCover(), config.getDefaultImage(),
                    systemInfo.getLogo()));
            var siteLogo = externalLinkProcessor.processLink(safeString(systemInfo.getLogo()));

            // 分类没有独立的作者和发布时间，作者使用站点名称，日期留空
            return new SeoData(title, description, coverUrl, pageUrl, siteName, "", "", "", "",
                siteName, siteLogo, displayName);
        });
    }

    /**
     * 构建标签详情页 SEO 数据。
     *
     * <p>从 {@link Tag} 提取标签级 SEO 信息。标签没有 description 字段，
     * 描述使用 "标签: displayName" 构造。标签也没有独立的作者和发布时间。
     *
     * <p>字段映射：
     * <ul>
     *   <li>标题 ← {@code tag.spec.displayName} + " - " + 站点名称</li>
     *   <li>描述 ← "标签: " + displayName（Tag 无 description 字段）</li>
     *   <li>封面 ← {@code tag.spec.cover}，回退到默认封面/站点 Logo</li>
     *   <li>URL ← {@code tag.status.permalink}</li>
     *   <li>关键词 ← 标签显示名</li>
     * </ul>
     *
     * @param tag 标签 Extension 对象
     * @return 包含 SEO 数据的 Mono
     */
    private Mono<SeoData> buildSeoDataForTag(Tag tag) {
        return Mono.zip(settingConfigGetter.getBasicConfig(), systemInfoGetter.get()).map(tuple -> {
            var config = tuple.getT1();
            var systemInfo = tuple.getT2();

            var displayName = safeString(tag.getSpec().getDisplayName());
            var siteName = safeString(systemInfo.getTitle());
            var title = siteName.isBlank() ? displayName : displayName + " - " + siteName;
            // Tag 没有 description 字段，使用语义描述
            var description = "标签: " + displayName;

            var pageUrl = externalLinkProcessor.processLink(tag.getStatus().getPermalink());
            var coverUrl = externalLinkProcessor.processLink(
                firstNonBlank(tag.getSpec().getCover(), config.getDefaultImage(),
                    systemInfo.getLogo()));
            var siteLogo = externalLinkProcessor.processLink(safeString(systemInfo.getLogo()));

            // 标签没有独立的作者和发布时间
            return new SeoData(title, description, coverUrl, pageUrl, siteName, "", "", "", "",
                siteName, siteLogo, displayName);
        });
    }

    /**
     * 构建作者页 SEO 数据。
     *
     * <p>从 {@link User} 提取作者级 SEO 信息。注意 User 没有
     * {@code status.permalink} 字段，URL 通过 "/authors/" + metadata.name 手动构造。
     *
     * <p>字段映射：
     * <ul>
     *   <li>标题 ← {@code user.spec.displayName} + " - " + 站点名称</li>
     *   <li>描述 ← {@code user.spec.bio}，回退到 "作者: displayName"</li>
     *   <li>封面 ← {@code user.spec.avatar}，回退到默认封面/站点 Logo</li>
     *   <li>URL ← "/authors/" + {@code user.metadata.name}</li>
     *   <li>作者 ← {@code user.spec.displayName}</li>
     *   <li>关键词 ← 作者显示名</li>
     * </ul>
     *
     * @param user 用户 Extension 对象
     * @return 包含 SEO 数据的 Mono
     */
    private Mono<SeoData> buildSeoDataForAuthor(User user) {
        return Mono.zip(settingConfigGetter.getBasicConfig(), systemInfoGetter.get()).map(tuple -> {
            var config = tuple.getT1();
            var systemInfo = tuple.getT2();

            var displayName = safeString(user.getSpec().getDisplayName());
            var siteName = safeString(systemInfo.getTitle());
            var title = siteName.isBlank() ? displayName : displayName + " - " + siteName;
            var description = firstNonBlank(user.getSpec().getBio(), "作者: " + displayName);

            // User 没有 status.permalink，手动构造作者归档页 URL
            var pageUrl =
                externalLinkProcessor.processLink("/authors/" + user.getMetadata().getName());
            var coverUrl = externalLinkProcessor.processLink(
                firstNonBlank(user.getSpec().getAvatar(), config.getDefaultImage(),
                    systemInfo.getLogo()));
            var siteLogo = externalLinkProcessor.processLink(safeString(systemInfo.getLogo()));

            // 作者页的 author 字段即为用户自身
            return new SeoData(title, description, coverUrl, pageUrl, displayName, "", "", "", "",
                siteName, siteLogo, displayName);
        });
    }

    // ======================== SEO 标签生成管道 ========================

    /**
     * 根据 SEO 数据和插件配置生成完整的 HTML meta/script 标签。
     *
     * <p>所有页面类型的 SEO 数据最终汇入此方法，按插件设置依次生成：
     * <ol>
     *   <li>canonical 链接标签（帮助搜索引擎识别内容唯一地址）</li>
     *   <li>alternate 链接标签（多语言/多版本支持）</li>
     *   <li>Open Graph (OG) meta 标签（主流社交平台和搜索引擎）</li>
     *   <li>字节跳动 (Bytedance) meta 标签（头条系搜索引擎）</li>
     *   <li>百度时间因子 script 标签（百度搜索引擎）</li>
     *   <li>Schema.org JSON-LD 结构化数据（Google、Bing）</li>
     *   <li>Twitter Cards meta 标签（Twitter/X 平台）</li>
     * </ol>
     *
     * @param seoData SEO 数据记录
     * @param model HTML 输出模型
     * @param modelFactory Thymeleaf 模型工厂，用于创建 HTML 文本节点
     */
    private Mono<Void> generateSeoTags(SeoData seoData, IModel model, IModelFactory modelFactory) {
        return settingConfigGetter.getBasicConfig().doOnNext(config -> {
            var sb = new StringBuilder();

            // canonical 和 alternate 链接
            if (config.isEnableCanonicalLink()) {
                sb.append("<link rel=\"canonical\" href=\"")
                    .append(HtmlEscape.escapeHtml5(seoData.postUrl())).append("\" />\n");

                if (config.isEnableAlternateLink()) {
                    for (var altLink : config.getAlternateLinks()) {
                        sb.append("<link rel=\"alternate\" hreflang=\"")
                            .append(HtmlEscape.escapeHtml5(altLink.getLangCode()))
                            .append("\" href=\"").append(HtmlEscape.escapeHtml5(
                                altLink.getUrlTemplate().replace("%URL%", seoData.postUrl())))
                            .append("\" />\n");
                    }
                }
            }

            // OG meta 标签
            if (config.isEnableOGTimeFactor()) {
                sb.append(genOGMeta(seoData));
            }
            // 字节跳动 meta 标签
            if (config.isEnableMetaTimeFactor()) {
                sb.append(genBytedanceMeta(seoData.baiduPubDate(), seoData.baiduUpdDate()));
            }
            // 百度时间因子
            if (config.isEnableBaiduTimeFactor()) {
                sb.append(genBaiduScript(seoData.title(), seoData.postUrl(), seoData.baiduPubDate(),
                    seoData.baiduUpdDate()));
            }
            // Schema.org JSON-LD
            if (config.isEnableStructuredData()) {
                sb.append(genSchemaOrgScript(seoData));
            }
            // Twitter Cards
            if (config.isEnableTwitterCards()) {
                sb.append(genTwitterCards(seoData, config.getTwitterCardsType(),
                    config.getTwitterSiteUsername(), config.getTwitterSiteUserId(),
                    config.getTwitterCreatorUsername(), config.getTwitterCreatorUserId()));
            }

            model.add(modelFactory.createText(sb.toString()));
        }).then();
    }

    // ======================== 标签生成方法 ========================

    /**
     * 生成 Open Graph meta 标签。
     *
     * <p>OG 协议被 Facebook、LinkedIn、微信等主流社交平台支持，
     * 也被 Google、Bing 等搜索引擎用于增强搜索结果展示。
     *
     * @see <a href="https://ogp.me/">The Open Graph protocol</a>
     */
    private String genOGMeta(SeoData seoData) {
        return """
            <meta property="og:type" content="article" />
            <meta property="og:title" content="%s" />
            <meta property="og:description" content="%s" />
            <meta property="og:image" content="%s" />
            <meta property="og:url" content="%s" />
            <meta property="og:release_date" content="%s" />
            <meta property="og:modified_time" content="%s" />
            <meta property="og:author" content="%s" />
            """.formatted(HtmlEscape.escapeHtml5(seoData.title()),
            HtmlEscape.escapeHtml5(seoData.description()), seoData.coverUrl(), seoData.postUrl(),
            seoData.baiduPubDate(), seoData.baiduUpdDate(),
            HtmlEscape.escapeHtml5(seoData.author()));
    }

    /**
     * 生成字节跳动时间因子 meta 标签。
     *
     * <p>用于今日头条、抖音搜索等字节系产品的 SEO 优化。
     */
    private String genBytedanceMeta(String publishDate, String updateDate) {
        return """
            <meta property="bytedance:published_time" content="%s" />
            <meta property="bytedance:updated_time" content="%s" />
            """.formatted(publishDate, updateDate);
    }

    /**
     * 生成百度时间因子 JSON-LD script 标签。
     *
     * <p>使用百度专用的 cambrian.jsonld 上下文，帮助百度搜索引擎
     * 识别内容的发布和更新时间。
     *
     * @see <a href="https://ziyuan.baidu.com/college/courseinfo?id=2210">百度时间因子文档</a>
     */
    private String genBaiduScript(String title, String url, String publishDate, String updateDate) {
        return """
            <script type="application/ld+json">
            {
              "@context": "https://ziyuan.baidu.com/contexts/cambrian.jsonld",
              "@id": "%s",
              "title": "%s",
              "pubDate": "%s",
              "upDate": "%s"
            }
            </script>
            """.formatted(url, JsonEscape.escapeJson(title), publishDate, updateDate);
    }

    /**
     * 生成 Schema.org JSON-LD 结构化数据。
     *
     * <p>使用 BlogPosting 类型，被 Google、Bing 等搜索引擎用于
     * 富摘要（Rich Snippets）展示。
     *
     * @see <a href="https://schema.org/BlogPosting">Schema.org BlogPosting</a>
     */
    private String genSchemaOrgScript(SeoData seoData) {
        return """
            <script type="application/ld+json">
            {
              "@context": "https://schema.org",
              "@type": "BlogPosting",
              "mainEntityOfPage": {
                "@type": "WebPage",
                "@id": "%s"
              },
              "headline": "%s",
              "description": "%s",
              "datePublished": "%s",
              "dateModified": "%s",
              "author": {
                "@type": "Person",
                "name": "%s"
              },
              "publisher": {
                "@type": "Organization",
                "name": "%s",
                "logo": {
                  "@type": "ImageObject",
                  "url": "%s"
                }
              },
              "image": "%s",
              "url": "%s",
              "keywords": "%s"
            }
            </script>
            """.formatted(seoData.postUrl(), JsonEscape.escapeJson(seoData.title()),
            JsonEscape.escapeJson(seoData.description()), seoData.googlePubDate(),
            seoData.googleUpdDate(), JsonEscape.escapeJson(seoData.author()),
            JsonEscape.escapeJson(seoData.siteName()), seoData.siteLogo(), seoData.coverUrl(),
            seoData.postUrl(), JsonEscape.escapeJson(seoData.keywords()));
    }

    /**
     * 生成 Twitter Cards meta 标签。
     *
     * <p>Twitter/X 平台的内容卡片，当链接被分享时展示标题、描述和图片。
     * 支持 summary 和 summary_large_image 两种类型。
     * 站点/创作者的用户名和 ID 为可选字段，非空时才添加对应标签。
     *
     * @see
     * <a href="https://developer.twitter.com/en/docs/twitter-for-websites/cards/overview/abouts-cards">
     * Twitter Cards 文档</a>
     */
    private String genTwitterCards(SeoData seoData, String twitterCardsType,
        String twitterSiteUsername, String twitterSiteUserId, String twitterCreatorUsername,
        String twitterCreatorUserId) {
        StringBuilder sb = new StringBuilder();

        // card 类型标签始终添加
        sb.append("<meta name=\"twitter:card\" content=\"")
            .append(HtmlEscape.escapeHtml5(twitterCardsType)).append("\" />\n");

        // 站点 Twitter 用户名（可选）
        if (twitterSiteUsername != null && !twitterSiteUsername.trim().isEmpty()) {
            sb.append("<meta name=\"twitter:site\" content=\"")
                .append(HtmlEscape.escapeHtml5(twitterSiteUsername)).append("\" />\n");
        }
        // 站点 Twitter 用户 ID（可选）
        if (twitterSiteUserId != null && !twitterSiteUserId.trim().isEmpty()) {
            sb.append("<meta name=\"twitter:site:id\" content=\"")
                .append(HtmlEscape.escapeHtml5(twitterSiteUserId)).append("\" />\n");
        }
        // 创作者 Twitter 用户名（可选）
        if (twitterCreatorUsername != null && !twitterCreatorUsername.trim().isEmpty()) {
            sb.append("<meta name=\"twitter:creator\" content=\"")
                .append(HtmlEscape.escapeHtml5(twitterCreatorUsername)).append("\" />\n");
        }
        // 创作者 Twitter 用户 ID（可选）
        if (twitterCreatorUserId != null && !twitterCreatorUserId.trim().isEmpty()) {
            sb.append("<meta name=\"twitter:creator:id\" content=\"")
                .append(HtmlEscape.escapeHtml5(twitterCreatorUserId)).append("\" />\n");
        }

        // 标题、描述、图片标签始终添加
        sb.append("<meta name=\"twitter:title\" content=\"")
            .append(HtmlEscape.escapeHtml5(seoData.title())).append("\" />\n");
        sb.append("<meta name=\"twitter:description\" content=\"")
            .append(HtmlEscape.escapeHtml5(seoData.description())).append("\" />\n");
        sb.append("<meta name=\"twitter:image\" content=\"")
            .append(HtmlEscape.escapeHtml5(seoData.coverUrl())).append("\" />\n");

        return sb.toString();
    }

    // ======================== 辅助方法 ========================

    /**
     * 查询文章关联的标签名称，用逗号拼接作为 SEO 关键词。
     *
     * <p>通过 {@code post.spec.tags}（标签的 metadata.name 列表）批量查询
     * Tag 对象，提取 displayName 作为关键词。
     *
     * @param post 文章 Extension 对象
     * @return 逗号分隔的标签名字符串，无标签时返回空字符串
     */
    private Mono<String> findTagForPost(Post post) {
        var tagNames = post.getSpec().getTags();
        if (tagNames == null || tagNames.isEmpty()) {
            return Mono.just("");
        }

        var listOptions = new ListOptions();
        listOptions.setFieldSelector(
            FieldSelector.of(Queries.in("metadata.name", tagNames.toArray(new String[0]))));

        return client.listAll(Tag.class, listOptions, Sort.by(Sort.Order.asc("metadata.name"))).map(
                tag -> Optional.ofNullable(tag.getSpec().getDisplayName())
                    .orElse(tag.getMetadata().getName())).collectList()
            .map(list -> String.join(",", list)).onErrorReturn("").defaultIfEmpty("");
    }

    /**
     * 格式化时间戳为指定格式的字符串。
     *
     * @param instant 时间戳，为 null 时返回空字符串
     * @param formatter 日期格式化器
     * @param zoneId 时区
     * @return 格式化后的时间字符串，或空字符串
     */
    private String formatDateTime(Instant instant, DateTimeFormatter formatter, ZoneId zoneId) {
        return Optional.ofNullable(instant).map(inst -> inst.atZone(zoneId).format(formatter))
            .orElse("");
    }

    /**
     * 空安全字符串转换，将 null 转为空字符串。
     */
    private String safeString(String value) {
        return value == null ? "" : value;
    }

    /**
     * 返回第一个非空白（non-blank）的字符串，全部为空时返回空字符串。
     *
     * <p>用于实现 SEO 字段的优先级回退链，如：
     * {@code firstNonBlank(entityDesc, siteDesc, fallbackDesc)}
     */
    private String firstNonBlank(String... values) {
        for (var value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return "";
    }

    // ======================== 数据结构 ========================

    /**
     * SEO 数据记录，封装所有 SEO 标签生成所需的字段。
     *
     * <p>所有页面类型的 SEO 处理器最终将数据封装为此 record，
     * 然后交给 {@link #generateSeoTags} 统一输出。
     *
     * @param title 页面标题
     * @param description 页面描述/摘要
     * @param coverUrl 封面图 URL（已处理为完整外部链接）
     * @param postUrl 页面 URL（已处理为完整外部链接）
     * @param author 作者名称
     * @param baiduPubDate 百度格式发布时间
     * @param baiduUpdDate 百度格式更新时间
     * @param googlePubDate Google 格式发布时间
     * @param googleUpdDate Google 格式更新时间
     * @param siteName 站点名称
     * @param siteLogo 站点 Logo URL
     * @param keywords 关键词
     */
    private record SeoData(String title, String description, String coverUrl, String postUrl,
                           String author, String baiduPubDate, String baiduUpdDate,
                           String googlePubDate, String googleUpdDate, String siteName,
                           String siteLogo, String keywords) {
    }
}
