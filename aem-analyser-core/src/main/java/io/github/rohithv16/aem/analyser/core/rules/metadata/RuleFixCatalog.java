package io.github.rohithv16.aem.analyser.core.rules.metadata;

import io.github.rohithv16.aem.analyser.core.model.report.RuleId;

/**
 * Human-facing guidance for every rule: why the finding matters on Adobe Experience Manager and
 * what the fix looks like in real project code.
 *
 * <p>The switch over {@link RuleId} is exhaustive on purpose. Adding a rule without writing its
 * guidance is a compile error, so the published reference page cannot silently fall behind the
 * rule catalogue.</p>
 */
public final class RuleFixCatalog {

    /**
     * @param why       why the finding matters in an AEM project
     * @param steps     how to fix it, in prose
     * @param badLang   language label for the failing example (null when there is none)
     * @param badCode   the failing example
     * @param goodLang  language label for the fixed example (null when there is none)
     * @param goodCode  the fixed example
     */
    public record Fix(String why, String steps, String badLang, String badCode, String goodLang, String goodCode) {

        public boolean hasBadExample() {
            return badCode != null && !badCode.isBlank();
        }

        public boolean hasGoodExample() {
            return goodCode != null && !goodCode.isBlank();
        }
    }

    private RuleFixCatalog() {
    }

    public static Fix forRule(RuleId rule) {
        return switch (rule) {
            case AEM_DEAD_001 -> new Fix(
                    """
                    A configuration file whose PID matches no component, no @ObjectClassDefinition and no \
                    platform factory PID never takes effect. At best it is dead weight in the content \
                    package; at worst it is a typo that silently stops the configuration you meant to \
                    ship, and the service starts with framework defaults on AEM as a Cloud Service.""",
                    """
                    Either delete the file or rename it so its PID is the component's fully qualified class \
                    name (or a factory PID the component declares). Verify with the Configuration console \
                    that the service now reads your properties.""",
                    "text", "ui.config/.../config/com.acme.OldSearchService.cfg.json\n"
                            + "# class was renamed to SearchService; nothing reads this PID",
                    "text", "ui.config/.../config/com.acme.core.services.SearchService.cfg.json");
            case AEM_DEAD_002A -> new Fix(
                    """
                    A property that the component's @ObjectClassDefinition does not declare is dropped \
                    during binding. The configuration looks correct in the repository, so the symptom is \
                    an unexplained default value at runtime rather than a validation error.""",
                    """
                    Remove the property, correct its spelling, or add an @AttributeDefinition that declares \
                    it. Configuration and OCD must describe the same set of properties.""",
                    "json", """
                    {
                      "serachPath": "/content/dam"
                    }""",
                    "json", """
                    {
                      "searchPath": "/content/dam"
                    }""");
            case AEM_DEAD_002B -> new Fix(
                    """
                    An OCD attribute that no code reads is a setting users can change with no effect. It \
                    usually survives a refactor that removed the code path but not the interface method.""",
                    """
                    Inject the configuration and read the attribute, or delete it from the \
                    @ObjectClassDefinition so the configuration console stops offering a dead switch.""",
                    "java", """
                    @ObjectClassDefinition(name = "Search Service")
                    public @interface Config {

                        // nothing in the bundle calls this
                        int legacyTimeoutSeconds() default 30;
                    }""",
                    "java", """
                    @Activate
                    void activate(Config config) {
                        this.timeout = Duration.ofSeconds(config.timeoutSeconds());
                    }""");
            case AEM_DEAD_003A -> new Fix(
                    """
                    A registered OSGi service that nothing injects and that no framework calls is code the \
                    build still compiles, tests and deploys. On AEM as a Cloud Service it also keeps its \
                    dependencies and configuration alive.""",
                    """
                    Inject the service where it was meant to be used, drop the {@code service = ...} \
                    registration so the class becomes a plain component, or export its package when it is \
                    a deliberate API for other bundles.""",
                    "java", """
                    @Component(service = LegacyImportService.class)
                    public class LegacyImportService {
                        // no @Reference to this type anywhere in the reactor
                    }""",
                    "java", """
                    @Component
                    public class ImportJobConsumer implements JobConsumer {
                        // used through the Sling Job API, not through an injected reference
                    }""");
            case AEM_DEAD_003B -> new Fix(
                    """
                    A Sling Model that no template adapts from and that no resourceType binds is never \
                    instantiated. It is often a model that survived the deletion of its component.""",
                    """
                    Bind it with {@code @Model(resourceType = ...)} so the component uses it, adapt it \
                    explicitly from HTL with data-sly-use, or delete it.""",
                    "java", """
                    @Model(adaptables = Resource.class)
                    public class TeaserModel { }""",
                    "java", """
                    @Model(adaptables = Resource.class,
                           resourceType = "acme/components/teaser")
                    public class TeaserModel { }""");
            case AEM_DEAD_003C -> new Fix(
                    """
                    With {@code immediate = false} and no service interface, an OSGi component is delayed \
                    and nothing can ever obtain it, so its @Activate never runs. Any startup work it \
                    performs — schedulers, caches, listeners — silently does not happen.""",
                    """
                    Set {@code immediate = true} for components that must start with the bundle, or register \
                    a service interface so the component is created on first injection. Delete it if \
                    neither is intended.""",
                    "java", """
                    @Component(immediate = false)
                    public class CacheWarmer {
                        @Activate
                        void activate() { /* never called */ }
                    }""",
                    "java", """
                    @Component(immediate = true)
                    public class CacheWarmer {
                        @Activate
                        void activate() { warm(); }
                    }""");
            case AEM_DEAD_004 -> new Fix(
                    """
                    An HTL expression that names a class which does not exist fails at render time, not at \
                    build time, so the page breaks in the author or publish instance rather than in CI. It \
                    is usually a package move or a rename that missed the template.""",
                    """
                    Point data-sly-use at the current fully qualified class name, and confirm the class lives \
                    in a bundle the template's package can see.""",
                    "html", "<sly data-sly-use.model=\"com.acme.core.models.OldHeroModel\"/>",
                    "html", "<sly data-sly-use.model=\"com.acme.core.models.HeroModel\"/>");
            case AEM_DEAD_005 -> new Fix(
                    """
                    A client library no page, component or other clientlib includes is still built and \
                    deployed. Dead clientlibs usually carry JS and CSS that also hide unused configuration \
                    and third-party dependencies.""",
                    """
                    Reference the category from an HTL template ({@code data-sly-call} with \
                    {@code categories}) or from another clientlib's {@code categories} dependency, or delete \
                    the clientlib folder.""",
                    "xml", """
                    <jcr:root ... categories="[acme.legacy.grid]"/>""",
                    "html", """
                    <sly data-sly-call="${clientlib.css @ categories='acme.legacy.grid'}"/>""");
            case AEM_DUP_001 -> new Fix(
                    """
                    Two servlets that answer the same request make the response depend on registration \
                    order and service ranking. On a publish tier the result can differ between instances, \
                    which is the hardest class of bug to reproduce.""",
                    """
                    Merge the servlets, or give each one a distinct contract: a different resource type, \
                    selectors, extension or HTTP method. Reach for an explicit {@code service.ranking} only \
                    when one servlet must override a product servlet, and document why.""",
                    "java", """
                    @SlingServlet(paths = "/bin/acme/search", methods = "GET")
                    public class SearchServlet extends SlingAllMethodsServlet { }

                    @SlingServlet(paths = "/bin/acme/search", methods = "GET")
                    public class SearchServletV2 extends SlingAllMethodsServlet { }""",
                    "java", """
                    @SlingServlet(resourceTypes = "acme/components/search",
                                  selectors = "results", extensions = "json", methods = "GET")
                    public class SearchResultsServlet extends SlingAllMethodsServlet { }

                    @SlingServlet(resourceTypes = "acme/components/search",
                                  selectors = "suggest", extensions = "json", methods = "GET")
                    public class SearchSuggestServlet extends SlingAllMethodsServlet { }""");
            case AEM_DUP_002 -> new Fix(
                    """
                    Two files configuring the same PID in the same runmode context are both installed and \
                    the winner depends on install order. Values then differ between a fresh install and an \
                    incremental one — the classic "works on my instance" configuration bug.""",
                    """
                    Keep exactly one file per PID and runmode context and merge the properties into it. Split \
                    by runmode (config, config.author, config.publish) when the values genuinely differ.""",
                    "text", """
                    ui.config/.../config/com.acme.SearchService.cfg.json         (limit = 100)
                    ui.config/.../config/com.acme.core.SearchService.cfg.json    (limit = 250)""",
                    "text", """
                    ui.config/.../config/com.acme.core.services.SearchService.cfg.json   (limit = 250)""");
            case AEM_DUP_003 -> new Fix(
                    """
                    Two Sling Models bound to the same resourceType and adaptable means HTL can resolve \
                    either one; the winner is undefined. Exported JSON then differs depending on which \
                    model the model factory picked.""",
                    """
                    Keep one model per resource type, or separate them by selector, resource type or a \
                    condition, and let HTL choose explicitly.""",
                    "java", """
                    @Model(adaptables = Resource.class, resourceType = "acme/components/card")
                    public class CardModel { }

                    @Model(adaptables = Resource.class, resourceType = "acme/components/card")
                    public class CardViewModel { }""",
                    "java", """
                    @Model(adaptables = Resource.class, resourceType = "acme/components/card",
                           defaultInjectionStrategy = DefaultInjectionStrategy.OPTIONAL)
                    public class CardModel { }

                    @Model(adaptables = Resource.class, resourceType = "acme/components/card/view")
                    public class CardViewModel { }""");
            case AEM_DUP_010 -> new Fix(
                    """
                    Cloned code drifts: a fix applied to one copy silently misses the others, and every copy \
                    has to be re-tested. Duplication across bundles is usually a missing shared module.""",
                    """
                    Extract the duplicated block into a helper or an interface in the bundle that already \
                    owns the concept, and have both call sites use it.""",
                    null, null, null, null);
            case AEM_QUAL_001 -> new Fix(
                    """
                    Cloud Manager's CQBP-75 rejects path-bound servlets because they bypass Sling's \
                    resource-centric security model and cannot be dispatched or cached like a resource. \
                    They also break the dispatcher rules teams rely on for /content URLs.""",
                    """
                    Register the servlet by resourceType and address it through a resource, with selectors \
                    and extensions for the variants. For a redirect or a download that genuinely has no \
                    resource, use a Sling mapping and a filter instead of /bin paths.""",
                    "java", """
                    @SlingServlet(paths = "/bin/acme/products", methods = "GET")
                    public class ProductsServlet extends SlingAllMethodsServlet { }""",
                    "java", """
                    @Component(service = Servlet.class, property = {
                            ServletResolverConstants.SLING_SERVLET_RESOURCE_TYPES + "=acme/components/products",
                            ServletResolverConstants.SLING_SERVLET_SELECTORS + "=list",
                            ServletResolverConstants.SLING_SERVLET_EXTENSIONS + "=json"
                    })
                    public class ProductsServlet extends SlingAllMethodsServlet { }""");
            case AEM_QUAL_002 -> new Fix(
                    """
                    Implementing or extending a {@code @ProviderType} interface ties your bundle to product \
                    internals that AEM as a Cloud Service upgrades without notice; CQBP-84 fails the build \
                    for exactly this reason. Provider types are contracts you consume, not implement.""",
                    """
                    Depend on the interface and delegate to it instead of implementing it. When you need to \
                    decorate behaviour, wrap the product object or move the logic into your own service \
                    that takes the provider type as a dependency.""",
                    "java", """
                    public class CachedPage implements com.day.cq.wcm.api.Page {
                        // re-implements a provider type: breaks on upgrade
                    }""",
                    "java", """
                    @Component(service = PageTitleService.class)
                    public class PageTitleService {

                        @Reference
                        private ResourceResolverFactory resolverFactory;

                        public String titleFor(String path) throws LoginException {
                            Map<String, Object> auth =
                                    Map.of(ResourceResolverFactory.SUBSERVICE, "title-read");
                            try (ResourceResolver resolver =
                                         resolverFactory.getServiceResourceResolver(auth)) {
                                PageManager pageManager = resolver.adaptTo(PageManager.class);
                                Page page = pageManager == null ? null : pageManager.getPage(path);
                                return page == null ? "" : page.getTitle();
                            }
                        }
                    }""");
            case AEM_QUAL_003 -> new Fix(
                    """
                    AMSCORE-554 flags the Sling whiteboard scheduler. AEM as a Cloud Service expects Sling \
                    Scheduled Jobs, which are cluster-aware: a job runs once across the publish tier instead \
                    of once per instance, and it survives a restart.""",
                    """
                    Implement a JobConsumer for the topic and schedule the run through the Sling Job API \
                    instead of {@code Scheduler.addJob}.""",
                    "java", """
                    scheduler.add(new ImportJob(), options);""",
                    "java", """
                    @Component(service = JobConsumer.class, immediate = true,
                               property = JobConsumer.PROPERTY_TOPICS + "=acme/import")
                    public class ImportJobConsumer implements JobConsumer {

                        @Override
                        public JobResult process(Job job) {
                            return JobResult.OK;
                        }
                    }""");
            case AEM_QUAL_004 -> new Fix(
                    """
                    SlingHttpServletRequest, ResourceResolver and JCR Session are bound to one thread and one \
                    request. Stored in a component field, they leak across requests: closed resolvers are \
                    reused, sessions go stale after a restart, and the component is no longer thread-safe \
                    (Cloud Manager check AEM-3).""",
                    """
                    Keep only immutable, thread-safe collaborators in fields. Obtain request-scoped objects \
                    as parameters or from the request, and close them where they were created.""",
                    "java", """
                    @Component(service = NavigationService.class)
                    public class NavigationService {

                        private ResourceResolver resolver;   // one request's resolver, shared by all

                        @Activate
                        void activate(ResourceResolverFactory factory) { }
                    }""",
                    "java", """
                    @Component(service = NavigationService.class)
                    public class NavigationService {

                        @Reference
                        private ResourceResolverFactory resolverFactory;

                        public List<String> paths(Resource resource) {
                            // the caller owns the request-scoped resolver
                            return collect(resource);
                        }
                    }""");
            case AEM_QUAL_005 -> new Fix(
                    """
                    Administrative logins and {@code loginAdministrative} bypass every permission check and \
                    are rejected by AEM as a Cloud Service. They also hide \
                    the real ACLs a feature needs.""",
                    """
                    Create a dedicated service user with a ServiceUserMapper mapping and request a service \
                    resource resolver for that subservice, then grant the service user exactly the \
                    permissions the feature needs.""",
                    "java", """
                    ResourceResolver resolver = resolverFactory.getAdministrativeResourceResolver(null);""",
                    "java", """
                    Map<String, Object> authInfo =
                            Collections.singletonMap(ResourceResolverFactory.SUBSERVICE, "search");
                    try (ResourceResolver resolver =
                                 resolverFactory.getServiceResourceResolver(authInfo)) {
                        // runs as the mapped service user, with its own ACLs
                    }""");
            case AEM_CLOUD_008 -> new Fix(
                    """
                    In AEM as a Cloud Service the repository structure is decided at build time by the \
                    packages a deployment installs; /apps and /libs are immutable and "any attempt to \
                    change an immutable area at runtime fails". Code that wrote configuration, labels or \
                    counters into /apps for years on 6.5 keeps compiling and deploying, then throws \
                    AccessDeniedException (or silently drops the write) at the first request.""",
                    """
                    Move the node into the content package so it is installed with the release, or keep \
                    runtime state under a mutable path (/var, /conf, /content) that the service user is \
                    allowed to modify.""",
                    "java", """
                    ValueMap config =
                        resolver.getResource("/apps/acme/config").adaptTo(ModifiableValueMap.class);
                    config.put("lastRun", System.currentTimeMillis());""",
                    "java", """
                    // ui.content/src/main/content/jcr_root/apps/acme/config/.content.xml
                    <jcr:root xmlns:sling="http://sling.apache.org/jcr/sling/1.0"
                              jcr:primaryType="sling:Folder"/>

                    // Java: runtime state belongs under a mutable path
                    ValueMap state =
                        resolver.getResource("/var/acme/config").adaptTo(ModifiableValueMap.class);
                    state.put("lastRun", System.currentTimeMillis());""");
            case AEM_CLOUD_009 -> new Fix(
                    """
                    AEM as a Cloud Service deploys one package per application: the container module \
                    (usually all) embeds and installs every other package, and must carry no content of \
                    its own. A module added to the reactor but never to <embeddeds> still builds and \
                    still passes the pipeline - the deployment simply does not contain it, and an author \
                    discovers the missing dialog weeks later. A bundle embedded outside an .../install \
                    path is worse: it travels inside the container and is never installed or started, so \
                    every component it declares stays absent with no error.""",
                    """
                    Add the module to the container's <embeddeds> under an install path, follow structure \
                    packages through <repositoryStructurePackages>, and move any content out of the \
                    container. Remove modules from the reactor when they are no longer deployed.""",
                    "xml", """
                    <!-- all/pom.xml -->
                    <embeddeds>
                        <embedded>
                            <groupId>com.acme</groupId>
                            <artifactId>acme.ui.content2</artifactId>
                            <type>zip</type>
                            <target>/apps/acme-packages/content/install</target>
                        </embedded>
                        <embedded>
                            <groupId>com.acme</groupId>
                            <artifactId>acme.core</artifactId>
                            <target>/apps/acme-packages/application</target> <!-- no /install -->
                        </embedded>
                    </embeddeds>""",
                    "xml", """
                    <!-- all/pom.xml -->
                    <embeddeds>
                        <embedded>
                            <groupId>com.acme</groupId>
                            <artifactId>acme.ui.content2</artifactId>
                            <type>zip</type>
                            <target>/apps/acme-packages/content/install</target>
                        </embedded>
                        <embedded>
                            <groupId>com.acme</groupId>
                            <artifactId>acme.core</artifactId>
                            <target>/apps/acme-packages/application/install</target>
                        </embedded>
                    </embeddeds>""");
            case AEM_QUAL_006 -> new Fix(
                    """
                    Storing a request-scoped resolver or session in an OSGi component field keeps one \
                    request's state alive for every later request, which leaks resolvers and can serve \
                    stale content.""",
                    """
                    Obtain the resolver or session per request and close it in the same scope. The \
                    thread-safety owner for this check is AEM-QUAL-004 (ERROR); this id is retained so \
                    existing baselines and SARIF consumers keep working.""",
                    null, null, null, null);
            case AEM_COMP_001 -> new Fix(
                    """
                    Classic UI {@code dialog.xml} is not read by the Touch UI editor, so authors cannot edit \
                    the component in AEM as a Cloud Service. It also drags in Classic UI client libraries \
                    that are not available there.""",
                    """
                    Migrate the dialog to a Granite UI {@code _cq_dialog/.content.xml} with \
                    {@code sling:resourceType="cq/gui/components/authoring/dialog"}, then delete \
                    dialog.xml and check each field's {@code xtype} has a Granite equivalent.""",
                    "text", """
                    acme/components/hero/dialog.xml
                      <dialog jcr:primaryType="nt:unstructured">
                        <items jcr:primaryType="nt:unstructured">
                          <title jcr:primaryType="cq:Widget" xtype="textfield" fieldLabel="Title"/>
                        </items>
                      </dialog>""",
                    "xml", """
                    acme/components/hero/_cq_dialog/.content.xml
                      <jcr:root xmlns:sling="http://sling.apache.org/jcr/sling/1.0"
                                xmlns:cq="http://www.day.com/jcr/cq/1.0"
                                jcr:primaryType="nt:unstructured"
                                jcr:title="Hero"
                                sling:resourceType="cq/gui/components/authoring/dialog">
                        <content jcr:primaryType="nt:unstructured"
                                 sling:resourceType="granite/ui/components/coral/foundation/container">
                          <items jcr:primaryType="nt:unstructured">
                            <title jcr:primaryType="nt:unstructured"
                                   sling:resourceType="granite/ui/components/coral/foundation/form/textfield"
                                   fieldLabel="Title"
                                   name="./title"/>
                          </items>
                        </content>
                      </jcr:root>""");
            case AEM_PERF_001 -> new Fix(
                    """
                    A resolver obtained from the factory and never closed keeps its JCR session and its \
                    caches alive for the life of the JVM. AEM as a Cloud Service logs leaked resolvers and \
                    eventually refuses new ones, so the instance degrades instead of failing fast.""",
                    """
                    Always obtain the resolver in a try-with-resources block, or close it in a finally \
                    block, and never hand it to another thread.""",
                    "java", """
                    ResourceResolver resolver = resolverFactory.getServiceResourceResolver(authInfo);
                    Resource resource = resolver.getResource(path);
                    return resource.adaptTo(ValueMap.class);""",
                    "java", """
                    try (ResourceResolver resolver = resolverFactory.getServiceResourceResolver(authInfo)) {
                        Resource resource = resolver.getResource(path);
                        return resource == null ? Map.of() : resource.adaptTo(ValueMap.class);
                    }""");
            case AEM_PERF_002 -> new Fix(
                    """
                    A JCR Session opened through {@code repository.login()} or adapted from a resolver must \
                    be logged out. An open session pins the observation queue and any pending changes, and \
                    on AEM as a Cloud Service leaked sessions show up as repository connection pressure.""",
                    """
                    Close the session in a finally block, or avoid the session entirely: the resource \
                    resolver API covers almost every read and write a component needs.""",
                    "java", """
                    Session session = resolver.adaptTo(Session.class);
                    Node node = session.getNode(path);""",
                    "java", """
                    try (ResourceResolver resolver = resolverFactory.getServiceResourceResolver(authInfo)) {
                        Resource resource = resolver.getResource(path);
                        if (resource != null) {
                            ValueMap properties = resource.adaptTo(ValueMap.class);
                        }
                    }""");
            case AEM_PERF_003 -> new Fix(
                    """
                    A JCR-SQL2 or XPath query with no path or node type restriction forces Oak to walk every \
                    index it has. At scale that is the difference between a millisecond lookup and a \
                    traversal warning in the Cloud Service logs.""",
                    """
                    Add a {@code path=} or {@code type=} constraint (or {@code ISDESCENDANTNODE}) so Oak can \
                    use a property index, and confirm the index exists.""",
                    "sql2", """
                    SELECT * FROM [nt:base] AS s
                    WHERE [sling:resourceType] = 'acme/components/card'""",
                    "sql2", """
                    SELECT * FROM [nt:base] AS s
                    WHERE ISDESCENDANTNODE(s, '/content/acme') AND [sling:resourceType] = 'acme/components/card'""");
            case AEM_PERF_004 -> new Fix(
                    """
                    An unbounded query result can be enormous on a content repository that grows daily. It \
                    is the usual cause of a slow component that only shows up in production.""",
                    """
                    Bound the result set with {@code p.limit} (or {@code LIMIT} in JCR-SQL2) and paginate \
                    with {@code p.offset}. Report the total separately with {@code p.guessTotal} rather than \
                    materialising every hit.""",
                    "java", """
                    Map<String, String> params = Map.of(
                            "path", "/content/acme",
                            "type", "cq:Page",
                            "property", "jcr:content/sling:resourceType");""",
                    "java", """
                    Map<String, String> params = new HashMap<>();
                    params.put("path", "/content/acme");
                    params.put("type", "cq:Page");
                    params.put("p.limit", "50");
                    params.put("p.offset", String.valueOf(page * 50));
                    params.put("p.guessTotal", "1000");""");
            case AEM_CLOUD_001 -> new Fix(
                    """
                    A plaintext secret in a content package is readable by anyone with repository access, \
                    ends up in every environment's backup, and cannot be rotated without a code change. \
                    AEM as a Cloud Service refuses to start a service with a secret that was not supplied \
                    through a secret variable.""",
                    """
                    Replace the value with a secret placeholder and define the variable in Cloud Manager (or \
                    in the local {@code .env} for the SDK). Never commit the real value.""",
                    "json", """
                    {
                      "apiKey": "sk_live_9f3ac1e8b0"
                    }""",
                    "json", """
                    {
                      "apiKey": "$[secret:acme.apiKey]"
                    }""");
            case AEM_CLOUD_002 -> new Fix(
                    """
                    AEM as a Cloud Service has no JSP support: a .jsp file is simply not rendered, so the \
                    page that uses it breaks after migration. JSPs also bypass HTL's contextual escaping.""",
                    """
                    Rewrite the JSP as an HTL template with a Sling Model for the logic, then delete the JSP \
                    and any Classic UI client libraries it depended on.""",
                    "text", """
                    ui.apps/src/main/content/jcr_root/apps/acme/components/hero/hero.jsp""",
                    "html", """
                    ui.apps/src/main/content/jcr_root/apps/acme/components/hero/hero.html

                    <div class="hero" data-sly-use.model="com.acme.core.models.HeroModel">
                      <h2>${model.title}</h2>
                    </div>""");
            case AEM_CLOUD_003 -> new Fix(
                    """
                    {@code getServiceResourceResolver} only works when a ServiceUserMapper mapping exists \
                    for the bundle's subservice and the service user exists in the repository. Without the \
                    mapping the call fails at runtime with a LoginException, and the feature only breaks on \
                    the environment where the config is missing.""",
                    """
                    Declare the service user through repoinit and add the mapping in a configuration file \
                    for the ServiceUserMapperImpl.amended factory, then grant the ACLs the feature needs.""",
                    "text", """
                    // Java asks for subservice "search" but no mapping ships
                    resolverFactory.getServiceResourceResolver(
                            Map.of(ResourceResolverFactory.SUBSERVICE, "search"));""",
                    "text", """
                    ui.config/.../config/org.apache.sling.serviceusermapping.impl.ServiceUserMapperImpl.amended-acme.cfg.json
                      { "user.mapping": [ "com.acme.core:search=acme-search-service" ] }

                    ui.config/.../config/org.apache.sling.jcr.repoinit.RepositoryInitializer-acme.cfg.json
                      { "scripts": [ "create service user acme-search-service with path /home/users/acme" ] }""");
            case AEM_CLOUD_004 -> new Fix(
                    """
                    An API deprecated for AEM as a Cloud Service is removed or unmaintained in the product \
                    version the environment runs. The code compiles today and fails on the next upgrade.""",
                    """
                    Replace the call with its supported equivalent (for example the Sling \
                    ResourceResolverFactory instead of the deprecated CQ resolver helpers, and \
                    {@code com.adobe.granite} APIs instead of Classic UI ones). Check the AEM as a Cloud \
                    Service API reference for the replacement.""",
                    null, null, null, null);
            case AEM_CLOUD_005 -> new Fix(
                    """
                    Only the documented runmodes are applied by AEM as a Cloud Service. A configuration in \
                    an unrecognised folder is never activated, so the setting silently does not apply.""",
                    """
                    Move the file to a recognised folder — config, config.author, config.publish, config.dev, \
                    config.stage, config.prod — or delete it when the runmode no longer exists.""",
                    "text", """
                    ui.config/.../config.author.dev.local/com.acme.SearchService.cfg.json""",
                    "text", """
                    ui.config/.../config.author/com.acme.SearchService.cfg.json""");
            case AEM_CLOUD_006 -> new Fix(
                    """
                    The dispatcher is validated only when Cloud Manager deploys, so a broken include, a \
                    duplicated ServerName, a virtual host with no DocumentRoot or a monolithic \
                    dispatcher.any turns into a failed pipeline rather than a failed build. The same \
                    defects are also the usual cause of "works in dev, 404 in prod": the vhost never \
                    loads, or two vhosts answer for one host name and whichever loads last wins.""",
                    """
                    Create the missing include target or fix the path, give every virtual host its own \
                    ServerName, add {@code DocumentRoot "${DOCROOT}"} to each block, and split a legacy \
                    monolithic dispatcher.any into conf.dispatcher.d (farms, filters, cache, renders, \
                    virtualhosts) plus conf.d (vhosts, rewrites, variables).""",
                    "text", """
                    # dispatcher/src/conf.d/available_vhosts/acme.vhost
                    Include conf.d/includes/cache-rules.conf   # no such file
                    <VirtualHost *:80>
                        ServerName "www.acme.com"
                    </VirtualHost>""",
                    "text", """
                    # dispatcher/src/conf.d/available_vhosts/acme.vhost
                    Include conf.d/includes/cache.conf
                    <VirtualHost *:80>
                        ServerName "www.acme.com"
                        DocumentRoot "${DOCROOT}"
                    </VirtualHost>""");
            case AEM_CLOUD_007 -> new Fix(
                    """
                    Filter roots decide which repository paths a package installs. When two modules \
                    filter the same root, the package installed last wins, so content from the other \
                    package silently disappears in the deployed environment — and the build, the \
                    package manager and both modules' own tests all still pass. {@code mode="replace"} \
                    on a shared root makes that deletion explicit. A root at {@code /apps}, {@code \
                    /content/dam} or under {@code /libs} ships content the project does not own.""",
                    """
                    Give each content module a disjoint root under the project namespace. Where a root \
                    genuinely must be shared, use {@code mode="merge"} and include only the paths the \
                    module owns instead of the platform root itself.""",
                    "json", """
                    <!-- ui.apps/src/main/content/META-INF/vault/filter.xml -->
                    <workspaceFilter version="1.0">
                        <filter root="/content/acme" mode="replace"/>
                    </workspaceFilter>

                    <!-- ui.content/src/main/content/META-INF/vault/filter.xml -->
                    <workspaceFilter version="1.0">
                        <filter root="/content/acme" mode="merge"/>
                    </workspaceFilter>""",
                    "json", """
                    <!-- ui.apps/src/main/content/META-INF/vault/filter.xml -->
                    <workspaceFilter version="1.0">
                        <filter root="/apps/acme/components"/>
                    </workspaceFilter>

                    <!-- ui.content/src/main/content/META-INF/vault/filter.xml -->
                    <workspaceFilter version="1.0">
                        <filter root="/content/acme" mode="merge"/>
                    </workspaceFilter>""");
            case AEM_SEC_001 -> new Fix(
                    """
                    {@code context='unsafe'} switches off HTL's contextual escaping, and the expression is \
                    written into the page verbatim. With any author-controlled or request-derived value that \
                    is a stored XSS hole (CWE-79) on a publish tier.""",
                    """
                    Drop {@code unsafe} and let HTL pick the context from the surrounding markup. When you \
                    really must emit markup, use {@code context='html'} and pass it through a sanitiser such \
                    as the Granite XSS API.""",
                    "html", "<div class=\"footer\">${model.footerHtml @ context='unsafe'}</div>",
                    "html", "<div class=\"footer\">${model.footerHtml @ context='html'}</div>");
            case AEM_SEC_002 -> new Fix(
                    """
                    A request parameter that reaches {@code sendRedirect} or an outbound HTTP call lets an \
                    attacker choose the destination: an open redirect for phishing, or server-side request \
                    forgery against internal services (CWE-601).""",
                    """
                    Validate the destination against an allowlist of hosts or a fixed set of relative paths \
                    before redirecting, and never build an outbound URL from unvalidated input.""",
                    "java", """
                    String target = request.getParameter("next");
                    response.sendRedirect(target);""",
                    "java", """
                    String target = request.getParameter("next");
                    if (target != null && target.startsWith("/content/") && !target.startsWith("//")) {
                        response.sendRedirect(response.encodeRedirectURL(target));
                    } else {
                        response.sendRedirect("/content/acme/en.html");
                    }""");
            case AEM_COMP_002 -> new Fix(
                    """
                    Content and templates bind to a resource type by string. When the component behind that \
                    string is renamed or moved, AEM resolves nothing at request time: the page renders \
                    blank, and neither javac nor the content-package build notices.""",
                    """
                    Point the reference at a component that exists under the project's {@code /apps/<project>/} \
                    prefix, or restore the component directory. Search the content package for the old type \
                    after a rename so no author content is left behind.""",
                    "xml", """
                    <sly data-sly-resource="${ @ resourceType='acme/components/elastic/metatagrenderer'}"/>

                    <!-- no directory at ui.apps/.../apps/acme/components/elastic/metatagrenderer -->""",
                    "xml", """
                    <sly data-sly-resource="${ @ resourceType='acme/components/elasticsearch/metatagrenderer'}"/>""");
            case AEM_QUAL_008 -> new Fix(
                    """
                    A Declarative Services component with an unsatisfied mandatory reference never \
                    activates. The bundle stays Active and the code compiles because the target type is on \
                    the classpath, so the failure is invisible until the servlet 404s or the scheduled job \
                    never fires.""",
                    """
                    Register an implementation with {@code @Component(service = Target.class)}, or declare the \
                    reference {@code cardinality = ReferenceCardinality.OPTIONAL} when activation must not \
                    depend on it. Confirm in the Felix console that the component is satisfied.""",
                    "java", """
                    @Component(immediate = true, service = ElasticIndexOrchestrator.class)
                    public class ElasticIndexOrchestrator {
                        // ResourceResolverProvider is declared in this project...
                        @Reference private ResourceResolverProvider resolverProvider;
                    }
                    // ...but no @Component implements or registers it""",
                    "java", """
                    @Component(service = ResourceResolverProvider.class)
                    public class ResourceResolverProviderImpl implements ResourceResolverProvider {
                        public ResourceResolver obtainResourceResolver() { ... }
                    }""");
            case AEM_DEAD_007 -> new Fix(
                    """
                    Sightly resolves {@code data-sly-include} while rendering, so a renamed or mis-pathed \
                    include is invisible to the build. It surfaces only as an empty region on one page, long \
                    after the template was moved. An include cycle is worse: rendering recurses until the \
                    request is aborted.""",
                    """
                    Correct the include path to an existing template relative to the including file, or break \
                    the cycle by factoring the shared markup into a leaf template that both sides include.""",
                    "html", """
                    <!-- components/card/card.html -->
                    <sly data-sly-include="card-details.html"/>

                    <!-- components/card/details.html (renamed to card-details.html) -->""",
                    "html", """
                    <sly data-sly-include="card-details.html"/>""");
        };
    }
}
