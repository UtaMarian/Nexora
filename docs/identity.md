# Identity, single sign-on and HTTPS

NEXORA trusts one identity provider, **Keycloak**, over OpenID Connect. Keycloak connects everything else:

```
Browser ──HTTPS──▶ nginx (TLS) ─┬─▶ NEXORA web app, API, WebDAV
                                ├─▶ /auth → Keycloak ──┬─ Active Directory / LDAP  (users, groups, password checks)
                                │                      ├─ Kerberos / SPNEGO         (silent sign-on on domain PCs)
                                │                      └─ AD FS (SAML 2.0)          (corporate federation, MFA)
                                └─▶ /browser, /cool → Collabora (Office editor)
```

| How someone signs in | What checks the password | Used by |
| --- | --- | --- |
| "Sign in with your organization account" (OIDC, Authorization Code + PKCE) | Keycloak → AD/LDAP, Kerberos or AD FS | Web app |
| "Sign in with ADFS" (Keycloak brokers to AD FS with `kc_idp_hint`) | AD FS | Web app |
| HTTP Basic with the AD user name and password | Keycloak → AD/LDAP (password grant, cached 5 min) | WebDAV, desktop Office, mapped drives, scripts |
| `Authorization: Bearer <token>` issued by Keycloak | Signature, issuer and audience checks | Integrations, other services |
| Local account (`admin`) | NEXORA (bcrypt) | Break-glass; turn off with `NEXORA_LOCAL_LOGIN=false` |

## Users and groups

- **Users** are created in NEXORA at their first sign-in, and by the **directory sync** (every 15 minutes, or
  *Directory → Sync now*). Users removed or disabled in the directory are disabled in NEXORA.
- **AD groups** become NEXORA groups named by their path, e.g. `/Engineering/Backend`. Nested groups are kept,
  so granting access to `/Engineering` also covers members of `/Engineering/Backend`.
- **AD FS groups** (the `Group` claim) become groups named `idp:<name>`.
- Membership of directory groups follows the directory at every sign-in and sync. Add a directory group to a
  site's Owners/Members/Visitors, or grant it directly in *Manage access*. NEXORA's own site groups are never
  changed by the sync.
- Members of `NEXORA_ADMIN_GROUP` (default **NEXORA Admins**) are NEXORA administrators.
- A directory user can never take over a local account with the same user name. Sign-in is refused with an
  "account conflict" message until an administrator renames the local account.

## HTTPS

The `web` container terminates TLS for everything (NEXORA, Keycloak, Collabora) on `NEXORA_PUBLIC_HOST`.

- **Production:** put your certificate chain and key in `deploy/certs/tls.crt` and `deploy/certs/tls.key`.
- **Test machines:** run `deploy/scripts/make-dev-certs.sh [host ...]`, then trust `deploy/certs/ca.crt` once
  per machine (Windows: `certutil -user -addstore Root deploy\certs\ca.crt`). Without any certificate the
  container generates a self-signed one, and browsers will warn.

Windows and Office send passwords to WebDAV servers only over HTTPS with a trusted certificate, so desktop
Office and mapped drives need this.

## Connect Active Directory

In `deploy/.env`:

```ini
LDAP_URL=ldaps://dc01.corp.example.com:636
LDAP_VENDOR=ad
LDAP_BIND_DN=CN=svc-nexora,OU=Service Accounts,DC=corp,DC=example,DC=com
LDAP_BIND_PASSWORD=...
LDAP_USERS_DN=OU=Staff,DC=corp,DC=example,DC=com
LDAP_GROUPS_DN=OU=Groups,DC=corp,DC=example,DC=com
COMPOSE_PROFILES=                      # turn off the demo directory
```

Then run `docker compose up -d keycloak-provision backend`. The service account needs read access only.
Disabled AD accounts are skipped. Nested groups are resolved with `LDAP_MATCHING_RULE_IN_CHAIN`. Keycloak
re-syncs changed users every 5 minutes and does a full sync every hour; NEXORA pulls from Keycloak every 15
minutes.

For `ldaps://` with an internal CA, add the CA to Keycloak's truststore (see the Keycloak docs on
`truststore-paths`).

## Kerberos (silent sign-on on domain-joined PCs)

1. Create a service account, for example `svc-nexora-http`, and register the SPN:
   ```
   setspn -S HTTP/nexora.corp.example.com CORP\svc-nexora-http
   ktpass -out nexora.keytab -princ HTTP/nexora.corp.example.com@CORP.EXAMPLE.COM ^
          -mapUser CORP\svc-nexora-http -pass * -crypto AES256-SHA1 -ptype KRB5_NT_PRINCIPAL
   ```
2. Copy `nexora.keytab` and a `krb5.conf` for your realm to `deploy/keycloak/`. Keytabs are git-ignored.
3. In `.env`:
   ```ini
   KERBEROS_REALM=CORP.EXAMPLE.COM
   KERBEROS_PRINCIPAL=HTTP/nexora.corp.example.com@CORP.EXAMPLE.COM
   KEYCLOAK_JAVA_OPTS=-Djava.security.krb5.conf=/opt/keycloak/conf/nexora/krb5.conf
   ```
   Then run `docker compose up -d keycloak keycloak-provision backend`.
4. Browsers: put `https://nexora.corp.example.com` in the *Local intranet* zone (GPO). For Chrome/Edge, set the
   `AuthServerAllowlist` policy; for Firefox, set `network.negotiate-auth.trusted-uris`.

Users on domain PCs are then signed in without typing a password. Everyone else gets the Keycloak login page.

## AD FS

1. On the AD FS server, run `deploy/adfs/Register-NexoraRelyingParty.ps1 -NexoraUrl https://nexora.corp.example.com`.
   This adds the relying party trust from Keycloak's SAML metadata, with claim rules for UPN (as NameID),
   e-mail, names and groups.
2. In `.env`:
   ```ini
   ADFS_METADATA_URL=https://adfs.corp.example.com/FederationMetadata/2007-06/FederationMetadata.xml
   NEXORA_ADFS_BUTTON_ALIAS=adfs           # adds "Sign in with ADFS" to the NEXORA sign-in page
   ```
   Then run `docker compose up -d keycloak-provision backend`.

AD FS users are linked by UPN. If the same people also come from the LDAP connection, Keycloak's
*first broker login* flow asks them once to link the two accounts. To link automatically, configure that flow
in Keycloak.

## Desktop Office sign-in (Open in Word, mapped drives)

`NEXORA_OFFICE_SIGN_IN` in `.env` picks how Word, Excel and PowerPoint sign in over WebDAV:

| Value | What the user sees | Needs |
| --- | --- | --- |
| `basic` (default) | the Windows credential prompt; user name and password (local or directory account) | the NEXORA host in Office's Basic-prompt allow list (below) |
| `forms` | a NEXORA sign-in window inside Office (MS-OFBA) with the single sign-on buttons | nothing on the PCs |

Microsoft 365 Apps block Basic prompts by default ("Microsoft Office has blocked access … sign-in method that
may be unsecure"). For `basic`, allow the NEXORA host with the Group Policy *User Configuration → Administrative
Templates → Microsoft Office 2016 → Security Settings → Allow specified hosts to show Basic Authentication prompts
to Office apps*, or on one PC (elevated PowerShell, semicolon-separated host names):

```powershell
reg add HKCU\Software\Policies\Microsoft\Office.0\Common\Identity /v basichostallowlist /t REG_EXPAND_SZ /d "localhost" /f
```

Then close every Office app and open the document again. Windows Explorer's mapped drives always use the
Windows prompt.

## Troubleshooting

- **Sign-in comes back with an error:** run `NEXORA_SECURITY_LOG=TRACE docker compose up -d backend` and check
  `docker compose logs backend`.
- **The directory sync status** is shown under *Directory* (administrators), including the last error.
- **Keycloak admin console:** `https://<host>/auth/admin/`, reachable from internal networks only (nginx), with
  user `admin` and `KEYCLOAK_ADMIN_PASSWORD`.
- **Re-running the setup** with `docker compose up -d keycloak-provision` is safe: it only creates or updates.
