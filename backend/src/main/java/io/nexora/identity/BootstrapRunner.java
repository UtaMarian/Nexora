package io.nexora.identity;

import io.nexora.content.ContentService;
import io.nexora.content.Node;
import io.nexora.content.NodeRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * First-start provisioning: creates the bootstrap administrator and, when enabled, a small demo
 * data set (two users and an "Intranet" site) so a fresh install is immediately explorable.
 */
@Component
public class BootstrapRunner implements ApplicationRunner {
    private static final Logger log = LoggerFactory.getLogger(BootstrapRunner.class);

    private final PrincipalRepository principals;
    private final GroupMemberRepository members;
    private final NodeRepository nodes;
    private final ContentService content;
    private final io.nexora.portal.PortalService portal;
    private final PasswordEncoder encoder;
    private final String adminLogin;
    private final String adminPassword;
    private final boolean demoData;
    private final io.nexora.workflow.WorkflowIdentity workflow;
    private final AnonymousAccess anonymous;

    public BootstrapRunner(PrincipalRepository principals, GroupMemberRepository members, NodeRepository nodes,
                           io.nexora.workflow.WorkflowIdentity workflow, AnonymousAccess anonymous,
                           ContentService content, io.nexora.portal.PortalService portal, PasswordEncoder encoder,
                           @Value("${nexora.bootstrap.admin-login:admin}") String adminLogin,
                           @Value("${nexora.bootstrap.admin-password:}") String adminPassword,
                           @Value("${nexora.bootstrap.demo-data:false}") boolean demoData) {
        this.workflow = workflow;
        this.anonymous = anonymous;
        this.principals = principals;
        this.members = members;
        this.nodes = nodes;
        this.content = content;
        this.portal = portal;
        this.encoder = encoder;
        this.adminLogin = adminLogin;
        this.adminPassword = adminPassword;
        this.demoData = demoData;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        anonymous.visitor();
        workflow.user(); // made here, not in whichever transaction first needs it, which might roll back
        Principal admin = principals.findByKindAndLogin(Principal.Kind.USER, adminLogin).orElse(null);
        if (admin == null) {
            if (adminPassword == null || adminPassword.isBlank()) {
                log.warn("No administrator exists and nexora.bootstrap.admin-password is not set; "
                        + "set NEXORA_BOOTSTRAP_ADMIN_PASSWORD to create one");
                return;
            }
            admin = Principal.user(adminLogin, "Administrator", encoder.encode(adminPassword));
            admin.setAdmin(true);
            principals.save(admin);
            log.info("Created bootstrap administrator '{}'", adminLogin);
        }

        if (demoData && nodes.findLiveSites().isEmpty()) {
            Principal alice = demoUser("alice", "Alice Andersen");
            Principal bob = demoUser("bob", "Bob Becker");
            Node site = portal.createSite(CurrentUser.system(admin), "intranet", "Intranet",
                    "Company news, policies and shared documents", io.nexora.portal.SiteTemplate.INTRANET);
            principals.findByKindAndLogin(Principal.Kind.GROUP, "intranet-members")
                    .ifPresent(g -> members.save(new GroupMember(g.getId(), alice.getId())));
            principals.findByKindAndLogin(Principal.Kind.GROUP, "intranet-visitors")
                    .ifPresent(g -> members.save(new GroupMember(g.getId(), bob.getId())));
            Node docs = nodes.findLiveByPath(site.getPath() + "/" + ContentService.DEFAULT_LIBRARY).orElseThrow();
            content.createFolder(CurrentUser.system(admin), docs.getId(), "Policies");
            content.createFolder(CurrentUser.system(admin), docs.getId(), "Templates");
            log.info("Created demo data: users alice/alice-demo (Members), bob/bob-demo (Visitors), site /sites/intranet");
        }
    }

    private Principal demoUser(String login, String name) {
        return principals.findByKindAndLogin(Principal.Kind.USER, login).orElseGet(() ->
                principals.save(Principal.user(login, name, encoder.encode(login + "-demo"))));
    }
}
