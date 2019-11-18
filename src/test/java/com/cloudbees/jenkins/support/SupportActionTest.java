package com.cloudbees.jenkins.support;

import com.cloudbees.jenkins.support.api.Component;
import com.cloudbees.jenkins.support.filter.ContentFilters;
import com.cloudbees.jenkins.support.filter.ContentMappings;
import com.cloudbees.jenkins.support.impl.AboutJenkins;
import com.cloudbees.jenkins.support.util.SystemPlatform;
import com.gargoylesoftware.htmlunit.HttpMethod;
import com.gargoylesoftware.htmlunit.Page;
import com.gargoylesoftware.htmlunit.WebRequest;
import com.gargoylesoftware.htmlunit.WebResponse;
import com.gargoylesoftware.htmlunit.html.HtmlButton;
import com.gargoylesoftware.htmlunit.html.HtmlForm;
import com.gargoylesoftware.htmlunit.html.HtmlPage;
import hudson.ExtensionList;
import hudson.model.Label;
import hudson.model.Slave;
import hudson.util.RingBufferLogHandler;
import jenkins.model.Jenkins;
import org.apache.commons.io.IOUtils;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.JenkinsRule.WebClient;
import org.jvnet.hudson.test.LoggerRule;
import org.jvnet.hudson.test.MockAuthorizationStrategy;
import org.xml.sax.SAXException;

import javax.annotation.Nonnull;
import javax.inject.Inject;
import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import java.util.stream.Collectors;
import java.util.zip.ZipFile;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.not;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertThat;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * @author Kohsuke Kawaguchi
 */
public class SupportActionTest {
    @Rule
    public JenkinsRule rule = new JenkinsRule();

    @Rule
    public TemporaryFolder temp = new TemporaryFolder();

    @Rule
    public LoggerRule logger = new LoggerRule();
    
    @Inject
    SupportAction root;

    @Before
    public void setUp() {
        rule.jenkins.getInjector().injectMembers(this);
    }

    @Test
    public void download() throws IOException, SAXException {
        downloadBundle("/download?json={\"components\":1}");
    }

    @Test
    public void generateAllBundles() throws IOException, SAXException {
        downloadBundle("/generateAllBundles?json={\"components\":1}");
    }

    /**
     * Trying to remove not existing bundles will do nothing, just a message in the log. 
     * @throws IOException 
     * @throws SAXException
     */
    @Test
    public void deleteNotExistingBundleWillFail() throws IOException, SAXException {
        String bundle = "../config.xml";
        logger.record(SupportAction.class, Level.FINE).capture(1);
        deleteBundle(bundle, "admin");
        assertTrue(logger.getMessages().stream().anyMatch(m -> m.startsWith(String.format("The bundle to delete %s does not exist", bundle))));
    }

    /**
     * Trying to remove an existing bundle (a zip or log file in JH/support directory) will success.
     * @throws IOException
     */
    @Test
    public void deleteExistingBundleWillSucceed() throws IOException {
        // Create a zip file as if it is a support bundle
        Path bundle = createFakeSupportBundle();
        assertTrue(Files.exists(bundle));
        deleteBundle(bundle.getFileName().toString(), "admin");
        assertTrue(Files.notExists(bundle));
    }

    @Test
    public void deleteExistingBundleWithoutPermissionWillFail() throws IOException {
        // Create a zip file as if it is a support bundle
        Path bundle = createFakeSupportBundle();
        assertTrue(Files.exists(bundle));
        WebResponse response = deleteBundle(bundle.getFileName().toString(), "user");
        assertThat(response.getContentAsString(), containsString(String.format("user is missing the %s/%s permission", SupportAction.CREATE_BUNDLE.group.title, SupportAction.CREATE_BUNDLE.name)));
        assertThat(response.getStatusCode(), equalTo(403));
    }
    
    private Path createFakeSupportBundle() throws IOException {
        return Files.createTempFile(SupportPlugin.getRootDirectory().toPath(), "fake-bundle-", ".zip");
    }

    /**
     * Delete the bundle by requesting the <i>deleteBundles</i> page being logged in as user
     * @param bundle the bundle file to delete
     * @param user the user logged in
     * @return the page
     * @throws IOException when any exception creating the url to call
     */
    private WebResponse deleteBundle(String bundle, String user) throws IOException {
        rule.jenkins.setCrumbIssuer(null);

        rule.jenkins.setSecurityRealm(rule.createDummySecurityRealm());
        rule.jenkins.setAuthorizationStrategy(
                new MockAuthorizationStrategy()
                        .grant(Jenkins.ADMINISTER).everywhere().to("admin")
                        .grant(Jenkins.READ).everywhere().to("user")
        );
        WebClient wc = rule.createWebClient()
                .withBasicCredentials(user)
                .withThrowExceptionOnFailingStatusCode(false);

        WebRequest request = new WebRequest(new URL(rule.getURL()+ root.getUrlName() + "/deleteBundles?json={%22bundles%22:[{%22selected%22:+true,%22name%22:+%22" + bundle + "%22}]}"), HttpMethod.POST);
        return wc.getPage(request).getWebResponse();
    }
    
    private void downloadBundle(String s) throws IOException, SAXException {
        JenkinsRule.JSONWebResponse jsonWebResponse = rule.postJSON(root.getUrlName() + s, "");
        File zipFile = File.createTempFile("test", "zip");
        IOUtils.copy(jsonWebResponse.getContentAsStream(), Files.newOutputStream(zipFile.toPath()));
        ZipFile z = new ZipFile(zipFile);
        // Zip file is valid
    }

    /**
     * Integration test that simulates the user action of clicking the button to generate the bundle.
     *
     * <p>
     * If any warning is reported to j.u.l logger, treat that as a sign of failure, because
     * support-core plugin works darn hard to try to generate something in the presence of failing
     * {@link Component} impls.
     */
    @Test
    public void takeSnapshotAndMakeSureSomethingHappens() throws Exception {
        rule.createSlave("slave1","test",null).getComputer().connect(false).get();
        rule.createSlave("slave2","test",null).getComputer().connect(false).get();

        RingBufferLogHandler checker = new RingBufferLogHandler();
        Logger logger = Logger.getLogger(SupportPlugin.class.getPackage().getName());

        logger.addHandler(checker);

        try {
            WebClient wc = rule.createWebClient();
            HtmlPage p = wc.goTo(root.getUrlName());

            HtmlForm form = p.getFormByName("bundle-contents");
            HtmlButton submit = (HtmlButton) form.getElementsByTagName("button").get(0);
            Page zip = submit.click();
            File zipFile = File.createTempFile("test", "zip");
            IOUtils.copy(zip.getWebResponse().getContentAsStream(), Files.newOutputStream(zipFile.toPath()));

            ZipFile z = new ZipFile(zipFile);

            // check the presence of files
            // TODO: emit some log entries and see if it gets captured here
            assertNotNull(z.getEntry("about.md"));
            assertNotNull(z.getEntry("nodes.md"));
            assertNotNull(z.getEntry("nodes/master/thread-dump.txt"));

            if (SystemPlatform.LINUX == SystemPlatform.current()) {
                List<String> files = Arrays.asList("proc/swaps.txt",
                                                       "proc/cpuinfo.txt",
                                                       "proc/mounts.txt",
                                                       "proc/system-uptime.txt",
                                                       "proc/net/rpc/nfs.txt",
                                                       "proc/net/rpc/nfsd.txt",
                                                       "proc/meminfo.txt",
                                                       "proc/self/status.txt",
                                                       "proc/self/cmdline",
                                                       "proc/self/environ",
                                                       "proc/self/limits.txt",
                                                       "proc/self/mountstats.txt",
                                                       "sysctl.txt",
                                                       "dmesg.txt",
                                                       "userid.txt",
                                                       "dmi.txt");

                for (String file : files) {
                    assertNotNull(file +" was not found in the bundle",
                                  z.getEntry("nodes/master/"+file));
                }
            }
        } finally {
            logger.removeHandler(checker);
            for (LogRecord r : checker.getView()) {
                if (r.getLevel().intValue() >= Level.WARNING.intValue()) {
                    Throwable thrown = r.getThrown();
                    if (thrown != null)
                        thrown.printStackTrace(System.err);

                    fail(r.getMessage());
                }
            }
        }
    }

    @Test
    public void anonymizationSmokes() throws Exception {
        Slave node = rule.createSlave(Label.get("super_secret_node"));
        File bundleFile = temp.newFile();
        List<Component> componentsToCreate = Collections.singletonList(ExtensionList.lookup(Component.class).get(AboutJenkins.class));
        try (OutputStream os = Files.newOutputStream(bundleFile.toPath())) {
            ContentFilters.get().setEnabled(false);
            SupportPlugin.writeBundle(os, componentsToCreate);
            ZipFile zip = new ZipFile(bundleFile);
            String nodeComponentText = IOUtils.toString(zip.getInputStream(zip.getEntry("nodes.md")), StandardCharsets.UTF_8);
            assertThat("Node name should be present when anonymization is disabled",
                    nodeComponentText, containsString(node.getNodeName()));
        }
        bundleFile = temp.newFile();
        try (OutputStream os = Files.newOutputStream(bundleFile.toPath())) {
            ContentFilters.get().setEnabled(true);
            SupportPlugin.writeBundle(os, componentsToCreate);
            ZipFile zip = new ZipFile(bundleFile);
            String nodeComponentText = IOUtils.toString(zip.getInputStream(zip.getEntry("nodes.md")), StandardCharsets.UTF_8);
            assertThat("Node name should not be present when anonymization is enabled",
                    nodeComponentText, not(containsString(node.getNodeName())));
            String anonymousNodeName = ContentMappings.get().getMappings().get(node.getNodeName());
            assertThat("Anonymous node name should be present when anonymization is enabled",
                    nodeComponentText, containsString(anonymousNodeName));
        }
    }

    /**
     * Check if the zip loses the folders due to anonymize '/' because there is an object with such a name. For example, a label.
     * @throws Exception If exception happened during the test
     */
    @Test
    public void corruptZipTestBySlash() throws Exception {
        final String OBJECT_NAME = "slave";
        Slave node = rule.createSlave(OBJECT_NAME, "/", null);

        // Set the components to generate
        List<Component> componentsToCreate = Collections.singletonList(ExtensionList.lookup(Component.class).get(AboutJenkins.class));

        ZipFile zip = generateBundle(componentsToCreate, false);
        ZipFile anonymizedZip = generateBundle(componentsToCreate, true);

        bundlesMatch(zip, anonymizedZip, OBJECT_NAME, ContentMappings.get().getMappings().get(OBJECT_NAME));
    }

    /**
     * Check if the file names in the zip are corrupt due to anonymize '.' because there is an object with such a name.
     * For example, a label.
     * @throws Exception If exception happened during the test
     */
    @Test
    public void corruptZipTestByDot() throws Exception {
        final String OBJECT_NAME = "slave";
        Slave node = rule.createSlave(OBJECT_NAME, ".", null);
        // Set the components to generate
        List<Component> componentsToCreate = Collections.singletonList(ExtensionList.lookup(Component.class).get(AboutJenkins.class));

        ZipFile zip = generateBundle(componentsToCreate, false);
        ZipFile anonymizedZip = generateBundle(componentsToCreate, true);

        bundlesMatch(zip, anonymizedZip,  OBJECT_NAME, ContentMappings.get().getMappings().get(OBJECT_NAME));
    }

    /**
     * Check if the file names in the zip are corrupt due to anonymize words in the file names because there is an object
     * with such a name. For example, a label.
     * @throws Exception If exception happened during the test
     */
    @Test
    public void corruptZipTestByWordsInFileName() throws Exception {
        final String OBJECT_NAME = "slave";
        // Create a slave with very bad words
        Slave node = rule.createSlave(OBJECT_NAME, "active plugins checksums md5 items about nodes manifest errors", null);

        /* This words are in the stopWords, so they won't never be replaced
        "jenkins", "node", "master", "computer", "item", "label", "view", "all", "unknown", "user", "anonymous",
        "authenticated", "everyone", "system", "admin", Jenkins.VERSION
        */

        // Set the components to generate
        List<Component> componentsToCreate = Collections.singletonList(ExtensionList.lookup(Component.class).get(AboutJenkins.class));

        ZipFile zip = generateBundle(componentsToCreate, false);
        ZipFile anonymizedZip = generateBundle(componentsToCreate, true);

        bundlesMatch(zip, anonymizedZip, OBJECT_NAME, ContentMappings.get().getMappings().get(OBJECT_NAME));
    }

    /**
     * Checks if both bundles have the same file names. It fails if it's not the case.
     * @param zip The bundle generated without anonymization
     * @param anonymizedZip The bundle generated with anonymization
     */
    private void bundlesMatch(ZipFile zip, ZipFile anonymizedZip, String objectName, String anonymizedObjectName) {
        // Print every entry
        List<String> entries = getFileNamesFromBundle(zip);

        // Debugging
        //entries.stream().forEach(entry -> System.out.println(entry));
        //System.out.println("nodes.md: \n"+ getContentZipEntry(zip,"nodes.md"));

        List<String> anonymizedEntries = getFileNamesFromBundle(anonymizedZip);

        //The name of the node created becomes replaced, so we change it to how the anonymization process has left it
        List<String> anonymizedEntriesRestored = anonymizedEntries.stream().map(entry -> entry.replaceAll(anonymizedObjectName, objectName)).collect(Collectors.toList());

        // More debugging
        //System.out.println("Anonymized:");
        //entries.stream().forEach(entry -> System.out.println(entry));
        //System.out.println("nodes.md: \n"+ getContentZipEntry(zip,"nodes.md"));

        assertTrue("Bundles should have the same files but it's not the case.\nBundle:\n " + entries + "\nAnonymized:\n " + anonymizedEntriesRestored, anonymizedEntriesRestored.equals(entries));
    }

    private ZipFile generateBundle(List<Component> componentsToCreate, boolean enabledAnonymization) throws IOException {
        File bundleFile = temp.newFile();
        try (OutputStream os = Files.newOutputStream(bundleFile.toPath())) {
            ContentFilters.get().setEnabled(enabledAnonymization);
            SupportPlugin.writeBundle(os, componentsToCreate);
            ZipFile zip = new ZipFile(bundleFile);
            return zip;
        }
    }

    @Nonnull
    private List<String> getFileNamesFromBundle(ZipFile zip) {
        List<String> entries = new ArrayList<>(zip.size());
        zip.stream().forEach(entry -> entries.add(entry.getName()));
        return entries;
    }

}

