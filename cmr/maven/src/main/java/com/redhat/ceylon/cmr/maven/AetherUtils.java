/*
 * Copyright 2011 Red Hat inc. and third party contributors as noted
 * by the author tags.
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.redhat.ceylon.cmr.maven;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.xml.sax.SAXException;

import com.redhat.ceylon.cmr.api.ArtifactContext;
import com.redhat.ceylon.cmr.api.ArtifactOverrides;
import com.redhat.ceylon.cmr.api.CmrRepository;
import com.redhat.ceylon.cmr.api.DependencyOverride;
import com.redhat.ceylon.cmr.api.ModuleDependencyInfo;
import com.redhat.ceylon.cmr.api.ModuleVersionArtifact;
import com.redhat.ceylon.cmr.api.ModuleVersionDetails;
import com.redhat.ceylon.cmr.api.ModuleVersionResult;
import com.redhat.ceylon.cmr.api.Overrides;
import com.redhat.ceylon.cmr.api.PathFilterParser;
import com.redhat.ceylon.cmr.api.RepositoryManager;
import com.redhat.ceylon.cmr.ceylon.CeylonUtils;
import com.redhat.ceylon.cmr.impl.AbstractArtifactResult;
import com.redhat.ceylon.cmr.impl.LazyArtifactResult;
import com.redhat.ceylon.cmr.impl.MavenRepository;
import com.redhat.ceylon.cmr.impl.NodeUtils;
import com.redhat.ceylon.cmr.resolver.aether.AetherException;
import com.redhat.ceylon.cmr.resolver.aether.AetherResolver;
import com.redhat.ceylon.cmr.resolver.aether.AetherResolverImpl;
import com.redhat.ceylon.cmr.resolver.aether.DependencyDescriptor;
import com.redhat.ceylon.cmr.resolver.aether.ExclusionDescriptor;
import com.redhat.ceylon.cmr.spi.Node;
import com.redhat.ceylon.common.Backends;
import com.redhat.ceylon.common.log.Logger;
import com.redhat.ceylon.model.cmr.ArtifactResult;
import com.redhat.ceylon.model.cmr.ArtifactResultType;
import com.redhat.ceylon.model.cmr.Exclusion;
import com.redhat.ceylon.model.cmr.ModuleScope;
import com.redhat.ceylon.model.cmr.PathFilter;
import com.redhat.ceylon.model.cmr.RepositoryException;

/**
 * Aether utils.
 *
 * @author <a href="mailto:ales.justin@jboss.org">Ales Justin</a>
 */
class AetherUtils {

    private Logger log;
    private AetherResolver impl;

    AetherUtils(Logger log, String settingsXml, boolean offline, int timeout, String currentDirectory) {
        this.log = log;
        if(settingsXml == null)
            settingsXml = MavenUtils.getDefaultMavenSettings();
        impl = new AetherResolverImpl(currentDirectory, settingsXml, offline, timeout);
    }

    File findDependency(Node node) {
        final ArtifactResult result = findDependencies(null, node, true);
        return (result != null) ? result.artifact() : null;
    }

    public File getLocalRepositoryBaseDir() {
        return impl.getLocalRepositoryBaseDir();
    }

    ArtifactResult findDependencies(RepositoryManager manager, Node node) {
        return findDependencies(manager, node, null);
    }

    String[] nameToGroupArtifactIds(String name){
        final int p = name.lastIndexOf(":");
        if (p == -1) {
            return null;
        }
        final String groupId = name.substring(0, p);
        final String artifactId = name.substring(p + 1);
        return new String[]{groupId, artifactId};
    }
    
    private ArtifactResult findDependencies(RepositoryManager manager, Node node, Boolean fetchSingleArtifact) {
        final ArtifactContext ac = ArtifactContext.fromNode(node);
        if (ac == null)
            return null;

        final String name = ac.getName();
        String[] groupArtifactIds = nameToGroupArtifactIds(name);
        if (groupArtifactIds == null) {
            return null;
        }
        final String groupId = groupArtifactIds[0];
        final String artifactId = groupArtifactIds[1];
        final String version = ac.getVersion();

        String repositoryDisplayString = NodeUtils.getRepositoryDisplayString(node);
        CmrRepository repository = NodeUtils.getRepository(node);

        if (CeylonUtils.arrayContains(ac.getSuffixes(), ArtifactContext.LEGACY_SRC)) {
            return fetchWithClassifier(repository, groupId, artifactId, version, "sources", repositoryDisplayString);
        }

        return fetchDependencies(manager, repository, groupId, artifactId, version, fetchSingleArtifact != null ? fetchSingleArtifact : ac.isIgnoreDependencies(), repositoryDisplayString);
    }

    private ArtifactResult fetchDependencies(RepositoryManager manager, CmrRepository repository, 
    		String groupId, String artifactId, String version, 
    		boolean fetchSingleArtifact, String repositoryDisplayString) {
    	
        String classifier = null;
        Overrides overrides = repository.getRoot().getService(Overrides.class);
        ArtifactOverrides ao = null;
        log.debug("Overrides: "+overrides);
        ArtifactContext context = getArtifactContext(groupId, artifactId, version, null, null);
        if(overrides != null){
            ao = overrides.getArtifactOverrides(context);
            log.debug(" ["+context+"] => "+ao);
        }
        // entire replacement
        ArtifactContext replacementContext = null;
        if (ao != null && ao.getReplace() != null) {
            replacementContext = ao.getReplace().getArtifactContext();
        }else if(overrides != null){
            replacementContext = overrides.replace(context);
        }
        if(replacementContext != null){
            log.debug(String.format("[Maven-Overrides] Replacing %s with %s.", context, replacementContext));
            // replace fetched dependency
            String[] nameToGroupArtifactIds = nameToGroupArtifactIds(replacementContext.getName());
            if(nameToGroupArtifactIds != null){
                groupId = nameToGroupArtifactIds[0];
                artifactId = nameToGroupArtifactIds[1];
                version = replacementContext.getVersion();
                // new AO
                context = getArtifactContext(groupId, artifactId, version, null, null);
                ao = overrides.getArtifactOverrides(context);
            }
        }
        // version replacement
        if(overrides != null && overrides.isVersionOverridden(context)){
            version = overrides.getVersionOverride(context);
            context.setVersion(version);
        }
        // classifier replacement
        if(ao != null && ao.hasClassifier()){
            classifier = ao.getClassifier();
            log.debug("Using classifier "+classifier);
        }

        final String name = toCanonicalForm(groupId, artifactId);
        final String coordinates = toCanonicalForm(name, version);
        try {
        	DependencyDescriptor info = impl.getDependencies(groupId, artifactId, version, classifier, null, fetchSingleArtifact);
            if (info == null) {
                log.debug("No artifact found: " + coordinates);
                return null;
            }

            final SingleArtifactResult result;
            if (fetchSingleArtifact) {
                result = new SingleArtifactResult(repository, name, version, groupId, artifactId, 
                        info.getFile(), repositoryDisplayString);
            } else {
                final List<ArtifactResult> dependencies = new ArrayList<>();

                for (DependencyDescriptor dep : info.getDependencies()) {
                    String dGroupId = dep.getGroupId();
                    String dArtifactId = dep.getArtifactId();
                    String dVersion = dep.getVersion();
                    boolean export = false;
                    boolean optional = dep.isOptional();
                    boolean isCeylon = false;
                    ModuleScope scope = toModuleScope(dep);
                    ArtifactContext dContext = null;
                    if(overrides != null)
                        dContext = getArtifactContext(dGroupId, dArtifactId, dVersion, null, null);

                    if (overrides != null) {
                        if (overrides.isRemoved(dContext) 
                                || (ao != null && ao.isRemoved(dContext))) {
                            log.debug(String.format("[Maven-Overrides] Removing %s from %s.", dep, context));
                            continue; // skip dependency
                        }
                        if (ao != null && ao.isAddedOrUpdated(dContext)) {
                            log.debug(String.format("[Maven-Overrides] Replacing %s from %s.", dep, context));
                            continue; // skip dependency
                        }
                        ArtifactContext replace = overrides.replace(dContext);
                        if(replace != null){
                            dContext = replace;
                            String[] groupArtifactIds = nameToGroupArtifactIds(replace.getName());
                            if(groupArtifactIds == null){
                                isCeylon = true;
                            }else{
                                dGroupId = groupArtifactIds[0];
                                dArtifactId = groupArtifactIds[1];
                            }
                            dVersion = replace.getVersion();
                        }
                        if(ao != null){
                            if(ao.isShareOverridden(dContext))
                                export = ao.isShared(dContext);
                            if(ao.isOptionalOverridden(dContext))
                                optional = ao.isOptional(dContext);
                        }
                    }

                    // do we have a version update?
                    if(overrides != null && overrides.isVersionOverridden(dContext)){
                        dVersion = overrides.getVersionOverride(dContext);
                    }
                    
                    ArtifactResult dr;
                    if(isCeylon)
                        dr = createArtifactResult(manager, dContext.getName(), dVersion, 
                                dGroupId, dArtifactId,
                                export, optional, scope, repositoryDisplayString);
                    else
                        dr = createArtifactResult(manager, repository, dGroupId, dArtifactId, dVersion, 
                                export, optional, scope, repositoryDisplayString,
                                dep.getExclusions());
                    dependencies.add(dr);
                }

                if (ao != null) {
                    for (DependencyOverride addon : ao.getAdd()) {
                        ArtifactContext dContext = addon.getArtifactContext();
                        String dVersion = overrides.getVersionOverride(dContext);
                        dependencies.add(createArtifactResult(manager, repository, dContext, dVersion, 
                                addon.isShared(), addon.isOptional(), ModuleScope.COMPILE, repositoryDisplayString, null));
                        log.debug(String.format("[Maven-Overrides] Added %s to %s.", addon.getArtifactContext(), context));
                    }
                }

                result = new AetherArtifactResult(repository, name, version, groupId, artifactId, 
                        info.getFile(), dependencies, repositoryDisplayString);
            }

            if (ao != null && ao.getFilter() != null) {
                result.setFilter(PathFilterParser.parse(ao.getFilter()));
            }

            return result;
        } catch (IOException e) {
            throw new IllegalStateException(e);
        } catch (AetherException e) {
          log.debug("Could not resolve artifact [" + coordinates + "] : " + e);
          return null;
		}
    }

    public static ModuleScope toModuleScope(DependencyDescriptor dep) {
        if(dep.isRuntimeScope())
            return ModuleScope.RUNTIME;
        if(dep.isTestScope())
            return ModuleScope.TEST;
        if(dep.isProvidedScope())
            return ModuleScope.PROVIDED;
        return ModuleScope.COMPILE;
    }

    public void search(String groupId, String artifactId, String version, 
            boolean exactVersionMatch, ModuleVersionResult result, 
    		Overrides overrides, String repositoryDisplayString){

    	try{
    	    if(version == null || version.isEmpty()){
    	        List<String> versions = impl.resolveVersionRange(groupId, artifactId, "(,)");
    	        for(String resolvedVersion : versions){
    	            if(resolvedVersion != null && !resolvedVersion.isEmpty())
    	                addSearchResult(groupId, artifactId, resolvedVersion, result, overrides, repositoryDisplayString);
    	        }
    	    }else if(exactVersionMatch){
    	        addSearchResult(groupId, artifactId, version, result, overrides, repositoryDisplayString);
    	    }else{
    	        List<String> versions = impl.resolveVersionRange(groupId, artifactId, "["+version+",]");
    	        for(String resolvedVersion : versions){
    	            // make sure the version matches because with maven if we ask for [1,] we also get 2.x
    	            if(resolvedVersion != null && resolvedVersion.startsWith(version))
    	                addSearchResult(groupId, artifactId, resolvedVersion, result, overrides, repositoryDisplayString);
    	        }
    	    }
        } catch (AetherException e) {
            log.debug("Could not search for artifact versions [" + groupId+":"+artifactId+":"+version + "] : " + e);
  		}
    }

    private void addSearchResult(String groupId, String artifactId, String version, ModuleVersionResult result, 
            Overrides overrides, String repositoryDisplayString) 
                    throws AetherException {
        ArtifactOverrides artifactOverrides = null;
        if(overrides != null){
            ArtifactContext ctx = new ArtifactContext(MavenRepository.NAMESPACE, groupId+":"+artifactId, version);
            // see if this artifact is replaced
            ArtifactContext replaceContext = overrides.replace(ctx);
            if(replaceContext != null){
                String[] groupArtifactIds = nameToGroupArtifactIds(replaceContext.getName());
                if(groupArtifactIds == null)
                    return; // abort
                groupId = groupArtifactIds[0];
                artifactId = groupArtifactIds[1];
                version = replaceContext.getVersion();
                ctx = replaceContext;
            }else if(overrides.isVersionOverridden(ctx)){
                // perhaps its version is overridden?
                version = overrides.getVersionOverride(ctx);
                ctx.setVersion(version);
            }
            artifactOverrides = overrides.getArtifactOverrides(ctx);
        }
    	DependencyDescriptor info = impl.getDependencies(groupId, artifactId, version, null, "pom", false);
        if(info != null){
            StringBuilder description = new StringBuilder();
            StringBuilder licenseBuilder = new StringBuilder();
            collectInfo(info, description, licenseBuilder);
            Set<ModuleDependencyInfo> dependencies = new HashSet<>();
            Set<ModuleVersionArtifact> artifactTypes = new HashSet<>();
            artifactTypes.add(new ModuleVersionArtifact(".jar", null, null));
            Set<String> authors = new HashSet<>();
            for(DependencyDescriptor dep : info.getDependencies()){
                String namespace = MavenRepository.NAMESPACE;
                String depGroupId = dep.getGroupId();
                String depArtifactId = dep.getArtifactId();
                String depName = dep.getGroupId()+":"+dep.getArtifactId();
                String depVersion = dep.getVersion();
                boolean export = false;
                boolean optional = dep.isOptional();
                if(overrides != null){
                    ArtifactContext depCtx = new ArtifactContext(namespace, depName, dep.getVersion());
                    if(overrides.isRemoved(depCtx)
                            || (artifactOverrides != null 
                                && (artifactOverrides.isRemoved(depCtx)
                                        || artifactOverrides.isAddedOrUpdated(depCtx))))
                        continue;
                    ArtifactContext replaceCtx = overrides.replace(depCtx);
                    if(replaceCtx != null){
                        depCtx = replaceCtx;
                        namespace = replaceCtx.getNamespace();
                        depName = replaceCtx.getName();
                    }
                    if(overrides.isVersionOverridden(depCtx))
                        depVersion = overrides.getVersionOverride(depCtx);
                    if(artifactOverrides != null){
                        if(artifactOverrides.isShareOverridden(depCtx))
                            export = artifactOverrides.isShared(depCtx);
                        if(artifactOverrides.isOptionalOverridden(depCtx))
                            optional = artifactOverrides.isOptional(depCtx);
                    }
                }
                ModuleDependencyInfo moduleDependencyInfo = new ModuleDependencyInfo(namespace, depName, depVersion,
                        optional, export, Backends.JAVA, toModuleScope(dep));
                dependencies.add(moduleDependencyInfo);
            }
            if(artifactOverrides != null){
                for(DependencyOverride add : artifactOverrides.getAdd()){
                    ArtifactContext ac = add.getArtifactContext();
                    ModuleDependencyInfo moduleDependencyInfo = new ModuleDependencyInfo(
                            ac.getNamespace(),
                            ac.getName(), 
                            ac.getVersion(),
                            add.isOptional(), 
                            add.isShared(),
                            Backends.JAVA,
                            ModuleScope.COMPILE);
                    dependencies.add(moduleDependencyInfo);
                }
            }
            ModuleVersionDetails moduleVersionDetails = new ModuleVersionDetails(
                    MavenRepository.NAMESPACE,
                    groupId+":"+artifactId, version, 
                    groupId, artifactId,
                    description.length() > 0 ? description.toString() : null,
                    licenseBuilder.length() > 0 ? licenseBuilder.toString() : null,
                    authors, dependencies, artifactTypes , true, repositoryDisplayString);
            result.addVersion(moduleVersionDetails);
        }
    }

    private void collectInfo(DependencyDescriptor info, StringBuilder description, StringBuilder licenseBuilder) {
        File pomFile = info.getFile();
        if(pomFile != null && pomFile.exists()){
        	try(InputStream is = new FileInputStream(pomFile)) {
        		DocumentBuilderFactory dbFactory = DocumentBuilderFactory.newInstance();
        		DocumentBuilder dBuilder = dbFactory.newDocumentBuilder();
        		Document doc = dBuilder.parse(is);
        		doc.getDocumentElement().normalize();
        		Element root = doc.getDocumentElement();
        		MavenUtils.collectText(root, description, "name", "description", "url");
        		Element licenses = MavenUtils.getFirstElement(root, "licenses");
        		if(licenses != null){
        			Element license = MavenUtils.getFirstElement(licenses, "license");
        			if(license != null){
        			    MavenUtils.collectText(license, licenseBuilder, "name", "url");
        			}
        		}
        	} catch (IOException e) {
        		// ignore, no info
        		e.printStackTrace();
        	} catch (ParserConfigurationException e) {
        		// ignore, no info
        		e.printStackTrace();
        	} catch (SAXException e) {
        		// ignore, no info
        		e.printStackTrace();
        	}
        };
    }

    private ArtifactContext getArtifactContext(String groupId, String artifactId, String version, String packaging, String classifier){
        if(classifier != null && classifier.isEmpty())
            classifier = null;
        return Overrides.createMavenArtifactContext(groupId, artifactId, version,
                packaging, classifier);
    }

    protected ArtifactResult createArtifactResult(RepositoryManager manager, CmrRepository repository, 
            final ArtifactContext dCo, String version, 
            final boolean shared, boolean optional, ModuleScope scope, final String repositoryDisplayString,
            List<ExclusionDescriptor> exclusions) {
        String[] groupArtifactIds = nameToGroupArtifactIds(dCo.getName());
        if(groupArtifactIds == null)
            return createArtifactResult(manager, dCo.getName(), version, 
                    null, null,
                    shared, optional, scope, repositoryDisplayString);
        return createArtifactResult(manager, repository, groupArtifactIds[0], groupArtifactIds[1], version, 
                shared, optional, scope, repositoryDisplayString, exclusions);
    }

    protected ArtifactResult createArtifactResult(final RepositoryManager manager, CmrRepository repository, 
            final String groupId, final String artifactId, final String dVersion, 
            final boolean shared, final boolean optional, final ModuleScope scope, final String repositoryDisplayString,
            final List<ExclusionDescriptor> exclusions) {
        
        final String dName = toCanonicalForm(groupId, artifactId);

        return new MavenArtifactResult(repository, dName, dVersion, groupId, artifactId, repositoryDisplayString) {
            private ArtifactResult result;
            
            {
                if(exclusions != null){
                    List<Exclusion> ret = new ArrayList<>(exclusions.size());
                    for (ExclusionDescriptor xDescr : exclusions) {
                        ret.add(new Exclusion(xDescr.getGroupId(), xDescr.getArtifactId()));
                    }
                    setExclusions(ret);
                }
            }

            @Override
            public boolean exported() {
                return shared;
            }
            
            @Override
            public boolean optional() {
                return optional;
            }

            @Override
            public ModuleScope moduleScope() {
                return scope;
            }
            
            private synchronized ArtifactResult getResult() {
                if (result == null) {
                    result = fetchDependencies(manager, (CmrRepository) repository(), groupId, artifactId, dVersion, false, repositoryDisplayString);
                }
                return result;
            }

            protected File artifactInternal() throws RepositoryException {
                return getResult().artifact();
            }

            public List<ArtifactResult> dependencies() throws RepositoryException {
                return getResult().dependencies();
            }
        };
    }

    protected ArtifactResult createArtifactResult(RepositoryManager manager, final String module, final String dVersion,
            String groupId, String artifactId,
            final boolean shared, final boolean optional, ModuleScope scope, final String repositoryDisplayString) {

        return new LazyArtifactResult(manager, MavenRepository.NAMESPACE, module, dVersion,
                shared, optional, scope);
    }

    private ArtifactResult fetchWithClassifier(CmrRepository repository, String groupId, String artifactId, String version, String classifier, String repositoryDisplayString) {
        final String name = toCanonicalForm(groupId, artifactId);
        final String coordinates = toCanonicalForm(toCanonicalForm(toCanonicalForm(name, "jar"), classifier), version);
        try {
        	DependencyDescriptor info = impl.getDependencies(groupId, artifactId, version, classifier, "jar", true);
            if (info != null) {
                return new SingleArtifactResult(repository, name, version, groupId, artifactId, 
                        info.getFile(), repositoryDisplayString);
            }
        } catch (AetherException e) {
        	log.debug("Could not resolve " + classifier + " for artifact [" + coordinates + "] : " + e);
		}

        log.debug("No artifact found: " + coordinates);
        return null;
    }

    static String toCanonicalForm(String groupId, String artifactId) {
        return groupId + ":" + artifactId;
    }

    private static abstract class MavenArtifactResult extends AbstractArtifactResult {
        private String repositoryDisplayString;
        private String artifactId;
        private String groupId;

        protected MavenArtifactResult(CmrRepository repository, 
                String name, String version, String groupId, String artifactId, 
                String repositoryDisplayString) {
            super(repository, MavenRepository.NAMESPACE, name, version);
            this.repositoryDisplayString = repositoryDisplayString;
            this.groupId = groupId;
            this.artifactId = artifactId;
        }

        @Override
        public String groupId() {
            return groupId;
        }
        
        @Override
        public String artifactId() {
            return artifactId;
        }
        
        @Override
        public ArtifactResultType type() {
            return ArtifactResultType.MAVEN;
        }

        @Override
        public String repositoryDisplayString() {
            return repositoryDisplayString;
        }
    }

    private static class SingleArtifactResult extends MavenArtifactResult {
        private File file;

        private SingleArtifactResult(CmrRepository repository, String name, String version,
                String groupId, String artifactId, 
                File file, String repositoryDisplayString) {
            super(repository, name, version, groupId, artifactId, repositoryDisplayString);
            this.file = file;
        }

        protected File artifactInternal() throws RepositoryException {
            return file;
        }

        void setFilter(PathFilter filter) {
            setFilterInternal(filter);
        }

        public List<ArtifactResult> dependencies() throws RepositoryException {
            return Collections.emptyList();
        }
    }

    private static class AetherArtifactResult extends SingleArtifactResult {
        private List<ArtifactResult> dependencies;

        private AetherArtifactResult(CmrRepository repository, String name, String version,
                String groupId, String artifactId, 
                File file, List<ArtifactResult> dependencies, String repositoryDisplayString) {
            super(repository, name, version, groupId, artifactId, file, repositoryDisplayString);
            this.dependencies = dependencies;
        }

        public List<ArtifactResult> dependencies() throws RepositoryException {
            return Collections.unmodifiableList(dependencies);
        }
    }

    public DependencyDescriptor getDependencies(InputStream stream, String name, String version) throws IOException {
        return impl.getDependencies(stream, name, version);
    }

    public DependencyDescriptor getDependencies(File pomXml, String name, String version) throws IOException {
        return impl.getDependencies(pomXml, name, version);
    }
}
