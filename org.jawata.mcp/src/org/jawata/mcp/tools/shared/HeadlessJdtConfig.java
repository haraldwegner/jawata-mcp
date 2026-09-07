package org.jawata.mcp.tools.shared;

import org.eclipse.core.runtime.preferences.DefaultScope;
import org.eclipse.core.runtime.preferences.IEclipsePreferences;
import org.eclipse.core.runtime.preferences.InstanceScope;
import org.eclipse.jdt.core.manipulation.JavaManipulation;
import org.eclipse.jdt.internal.core.manipulation.CodeTemplateContextType;
import org.eclipse.jface.text.templates.Template;
import org.eclipse.text.templates.ContextTypeRegistry;
import org.eclipse.text.templates.TemplatePersistenceData;
import org.eclipse.text.templates.TemplateStoreCore;

/**
 * Sprint 23 (Stage 5) — one-shot headless configuration of
 * {@code org.eclipse.jdt.core.manipulation}. In the IDE the JDT-UI plug-in
 * performs this on activation; a headless embedder must do it itself (the
 * jdt.ls pattern), or manipulation-based refactorings NPE/IAE the first time
 * they read a preference: {@code JavaManipulation.getPreference} passes the
 * PREFERENCE NODE QUALIFIER to {@code ProjectScope.getNode}, and an unset
 * (null) qualifier is rejected with {@code IllegalArgumentException} —
 * observed live on {@code SelfEncapsulateFieldRefactoring.initialize →
 * GetterSetterUtil.getGetterName → StubUtility.useIsForBooleanGetters}.
 */
public final class HeadlessJdtConfig {

    private static volatile boolean initialized;

    /**
     * Idempotent; cheap enough to call before any manipulation refactoring.
     *
     * <h2>The latch does not cover the template store, and that is the point</h2>
     *
     * <p>Everything below the latch is installed on the STATIC side of JDT and survives for
     * the life of the process. The code-template store does not: it is built against
     * {@code InstanceScope}, which belongs to the Eclipse WORKSPACE, and the workspace can be
     * torn down and rebuilt under a process that has already initialised. When that happens
     * the latch still says "done" while the store is gone — so
     * {@code CodeGeneration.getSetterMethodBodyContent} returns null again and
     * {@code SelfEncapsulateFieldRefactoring} takes the fallback path into the upstream bug
     * this class exists to make unreachable. The store is therefore checked on EVERY call,
     * against its own state rather than against a memory of having installed it.</p>
     *
     * <p>Measured in Sprint 28d-rescue Stage 5. {@code data kind=encapsulate_record} runs
     * self-encapsulate once per public field, so it was the first operation to reach this
     * path twice in one process across two workspaces. The symptom is the exact one the
     * store's own comment below predicts — <em>Assignment is not an instance of
     * Statement</em>, from {@code createSetterMethod} — and it appears only when something
     * else built a workspace first, which is why a class that passes alone fails in company.
     * </p>
     */
    public static void ensureInitialized() {
        installStatics();
        // NOT behind the latch, and NOT a null check. What matters is whether the store
        // ANSWERS: a store object can outlive the workspace whose preference node backs it,
        // and a present-but-mute store takes JDT down the same fallback as an absent one.
        // The condition is therefore the property the caller depends on — that the setter
        // stub resolves — rather than a proxy for it.
        if (!setterTemplateResolves()) {
            synchronized (HeadlessJdtConfig.class) {
                if (!setterTemplateResolves()) {
                    org.slf4j.LoggerFactory.getLogger(HeadlessJdtConfig.class).warn(
                        "JDT code-template store does not answer for the setter stub"
                            + " (store={}); reinstalling. Without it"
                            + " SelfEncapsulateFieldRefactoring takes its fallback path into"
                            + " an upstream bug.",
                        JavaManipulation.getCodeTemplateStore() == null ? "absent" : "present");
                    installCodeTemplates();
                }
            }
        }
    }

    /** Whether the store is there AND still hands back the template JDT will ask for. */
    private static boolean setterTemplateResolves() {
        TemplateStoreCore store = JavaManipulation.getCodeTemplateStore();
        return store != null
            && store.findTemplateById(CodeTemplateContextType.SETTERSTUB_ID) != null;
    }

    /** The process-lifetime half: preference ids, defaults, and the member-order cache. */
    private static void installStatics() {
        if (initialized) return;
        synchronized (HeadlessJdtConfig.class) {
            if (initialized) return;
            if (JavaManipulation.getPreferenceNodeId() == null) {
                JavaManipulation.setPreferenceNodeId("org.jawata.jdt");
            }
            // Preferences JDT-UI seeds on activation and manipulation code
            // reads UNGUARDED (null → NPE, e.g. the member-sort-order string
            // in getter/setter generation). Same defaults jdt.ls installs.
            IEclipsePreferences defaults =
                DefaultScope.INSTANCE.getNode(JavaManipulation.getPreferenceNodeId());
            defaults.put("outlinesortoption", "T,SF,SI,SM,F,I,C,M");
            defaults.put("org.eclipse.jdt.ui.visibility.order", "B,V,R,D");
            // CodeStyleConfiguration.configureImportRewrite reads these
            // UNGUARDED (order.endsWith NPEs on a missing default).
            defaults.put("org.eclipse.jdt.ui.importorder", "java;javax;org;com");
            defaults.put("org.eclipse.jdt.ui.ondemandthreshold", "99");
            defaults.put("org.eclipse.jdt.ui.staticondemandthreshold", "99");
            // jawata-mcp#16 — the ADD-missing-import half of organize_imports.
            //
            // `TypeNameMatchCollector.getStringMatchers` reads this and passes it STRAIGHT
            // into `new StringTokenizer(str, ";")`, so unset means null means NPE — and the
            // add path is the only one that reaches it, because only an add runs a type-name
            // SEARCH. That is why removing and sorting always worked and adding never did.
            //
            // It was not found by reading OrganizeImportsOperation, JavaPreferencesSettings
            // or CodeStyleConfiguration — the recorded divergence says so in as many words.
            // It is in none of them: it is a TYPE FILTER, an IDE convenience for hiding
            // names from a search, and the import machinery only touches it by going
            // through the search engine.
            //
            // EMPTY is the correct value rather than a placeholder: it tokenizes to nothing,
            // so no type is filtered out of the search, which is what a headless server
            // should do. A non-empty default here would silently hide types from every
            // add-import the product performs.
            defaults.put("org.eclipse.jdt.ui.typefilter.enabled", "");

            // Member-order cache: in the IDE, jdt.ui installs it on activation;
            // headless embedders must install() it themselves or member
            // insertion NPEs on fPreferences — hit live by
            // ExtractSupertypeProcessor placing members into the created
            // supertype. install() registers the instance with
            // JavaManipulationPlugin and reads the defaults seeded above.
            new org.eclipse.jdt.internal.core.manipulation.MembersOrderPreferenceCacheCommon()
                .install();

            // FORMATTER TAB CHAR IS NOT SET HERE, and BOTH central routes have now
            // been tried and measured.
            //
            // v2.14.1 #5 tried JavaCore.setOptions — the INSTANCE scope — and it did
            // not reach the code-generating rewrites. That is why FormatterOptions
            // exists and is passed per rewrite to ASTRewrite.rewriteAST(doc, opts),
            // which IS load-bearing and fixes the nine rules we own.
            //
            // Sprint 28d-rescue tried the remaining one: the DEFAULT scope on
            // JavaCore.PLUGIN_ID, which a project's own setting would still override.
            // It does not reach ExtractMethodRefactoring either. Measured by the row-8
            // parity golden, which pins the three generated methods and did not move.
            //
            // AND THE ANSWER WAS A CONSTRUCTOR ARGUMENT, not a preference. The JDT
            // refactorings take a formatter-options map on an overload nobody here was
            // calling: ExtractMethodRefactoring, ExtractTempRefactoring and
            // ExtractConstantRefactoring all have one. They are given
            // FormatterOptions.forGeneratedCode now, and every golden in the tree is
            // free of tabs.
            //
            // The two failed attempts are kept above because they are what makes the
            // third one obvious: the setting is not global, it is per call, and the
            // engine that emits the code is the thing that must be told.
            //
            // STILL OPEN, and named so the count does not restart: two sites use
            // ASTRewrite's NO-ARGUMENT rewriteAST(), which reads the type root's own
            // options and hits the same default —
            // ExtractSuperclassTool.java:530 and ApplyNullAnnotationsTool.java:273,432.
            // Fixing them means giving each a Document and an options map, as the
            // statement rules have, and both tools carry parity goldens that would need
            // re-recording with a divergence entry. They are outside Stage 3 and were
            // left rather than swept in at the end of it.
            initialized = true;
        }
    }

    /**
     * The WORKSPACE-lifetime half.
     *
     * <p>Without a store, {@code CodeGeneration.get*BodyContent} returns null and
     * {@code SelfEncapsulateFieldRefactoring}'s fallback path hits an upstream bug — a bare
     * {@code Assignment} added where a {@code Statement} is required. Registering the
     * IDE-default stub bodies makes the template path work and the fallback unreachable.</p>
     *
     * <p>Called whenever the store is absent rather than once per process, because the store
     * is bound to {@code InstanceScope} and does not outlive its workspace. See
     * {@link #ensureInitialized()}.</p>
     */
    private static void installCodeTemplates() {
        ContextTypeRegistry registry = new ContextTypeRegistry();
                CodeTemplateContextType.registerContextTypes(registry);
                TemplateStoreCore store = new TemplateStoreCore(registry,
                    InstanceScope.INSTANCE.getNode(JavaManipulation.getPreferenceNodeId()),
                    "code_templates");
                addTemplate(store, CodeTemplateContextType.GETTERSTUB_ID, "getterstub",
                    CodeTemplateContextType.GETTERBODY_CONTEXTTYPE, "return ${field};");
                addTemplate(store, CodeTemplateContextType.SETTERSTUB_ID, "setterstub",
                    CodeTemplateContextType.SETTERBODY_CONTEXTTYPE, "${field} = ${param};");
                addTemplate(store, CodeTemplateContextType.METHODSTUB_ID, "methodstub",
                    CodeTemplateContextType.METHODBODY_CONTEXTTYPE,
                    "// ${todo} Auto-generated method stub\n${body_statement}");
                addTemplate(store, CodeTemplateContextType.CONSTRUCTORSTUB_ID, "constructorstub",
                    CodeTemplateContextType.CONSTRUCTORBODY_CONTEXTTYPE,
                    "// ${todo} Auto-generated constructor stub\n${body_statement}");
                addTemplate(store, CodeTemplateContextType.CATCHBLOCK_ID, "catchblock",
                    CodeTemplateContextType.CATCHBLOCK_CONTEXTTYPE,
                    "// ${todo} Auto-generated catch block\n${exception_var}.printStackTrace();");

                // NEWTYPE + CLASSBODY — the templates needed to generate a WHOLE NEW
                // FILE, as opposed to a member body. Added at Stage 7 (S7.0), measured
                // rather than anticipated.
                //
                // Without them CodeGeneration.getCompilationUnitContent returns NULL,
                // and JDT hands that null straight to Buffer.setContents:
                //
                //   NullPointerException: Cannot invoke "String.toCharArray()"
                //     because "newContents" is null
                //     at ParameterObjectFactory.createTopLevelParameterObject
                //     at ExtractClassRefactoring.createChange
                //
                // The failure shape is the point. It happens in createChange, AFTER
                // checkInitialConditions AND checkFinalConditions both return clean —
                // every precondition green and nothing to apply. Any refactoring that
                // creates a top-level type headlessly hits this, so it is fixed here
                // in the shared config rather than at one call site.
                addTemplate(store, CodeTemplateContextType.NEWTYPE_ID, "newtype",
                    CodeTemplateContextType.NEWTYPE_CONTEXTTYPE,
                    "${package_declaration}\n\n${type_declaration}");
                addTemplate(store, CodeTemplateContextType.CLASSBODY_ID, "classbody",
                    CodeTemplateContextType.CLASSBODY_CONTEXTTYPE, "");

        JavaManipulation.setCodeTemplateStore(store);
        JavaManipulation.setCodeTemplateContextRegistry(registry);
    }

    private static void addTemplate(TemplateStoreCore store, String id, String name,
                                    String contextTypeId, String pattern) {
        Template template = new Template(name, "", contextTypeId, pattern, true);
        store.add(new TemplatePersistenceData(template, true, id));
    }

    private HeadlessJdtConfig() {}
}
