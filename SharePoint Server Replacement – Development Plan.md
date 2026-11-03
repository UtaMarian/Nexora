# SharePoint Server Replacement – Development Plan

Nov 3, 2026 · @Flash

## Executive summary

Build a self-hosted, Linux/container-native document and intranet platform that reaches a sellable MVP in 9 months and SharePoint-migration parity for the core 80% of usage in 24 months, with a team growing from 12 to about 35 engineers.

**Why now.** SharePoint Server 2016 and 2019 left extended support on 14 July 2026, and Microsoft offers no paid extended security updates for them ([endoflife.ai](https://endoflife.ai/article-sharepoint-2016-2019-eol)). The only supported on-prem path left is Subscription Edition, which requires an active subscription or Software Assurance ([Stefan Gossner](https://blog.stefan-gossner.com/2023/06/29/important-considerations-for-an-upgrade-from-sharepoint-server-2016-to-2019-or-subscription-edition/)). Organizations that must stay on-prem (government, defense, healthcare, regulated finance, sovereign-cloud mandates in the EU) now face a forced decision, and many are open to a non-Microsoft option.

**The bet.** Do not clone SharePoint. Win on three things SharePoint Server does poorly: simple operations (one Helm chart instead of a Windows/SQL farm), painless migration from existing farms, and data sovereignty with no cloud dependency.

**Biggest risk.** Office document co-authoring and Outlook/Teams integration. The plan addresses it with WOPI-compatible editors (Collabora/OnlyOffice) and WebDAV/Office-URI support from the MVP onward.

## Product vision and scope

The product is a sovereign collaboration platform: sites, document libraries, lists, search and workflows that an IT team of two can run on its own hardware.

**Target customers (in order of priority)**

1. Mid-size regulated organizations (500–10,000 users) on SharePoint 2013/2016/2019 who must stay on-prem: public sector, defense suppliers, hospitals, banks, insurers.
2. EU organizations with data-sovereignty or NIS2/GDPR pressure that want to exit Microsoft licensing.
3. Air-gapped and classified environments (later, needs certifications).

**In scope**

- Team and communication sites, pages, navigation, hubs
- Document libraries: versioning, check-in/out, metadata, content types, retention
- Lists with custom columns, views and forms (the real workhorse of most farms)
- Permissions model compatible with SharePoint (inheritance, groups, AD/LDAP/SAML/OIDC)
- Enterprise search across all content
- Workflows and approvals (replacing SharePoint Designer 2013 and Nintex-style flows)
- Office editing in browser and desktop; Windows Explorer / mapped-drive access
- Migration tooling from SharePoint 2010–SE

**Explicitly out of scope (v1)**

- Running SharePoint farm solutions (WSP), server-side code or SharePoint Add-ins as-is
- Full InfoPath compatibility (convert to native forms instead)
- Project Server, PerformancePoint, Excel/Visio Services, BCS
- Chat and meetings (integrate with Matrix, Mattermost or Teams instead)

## Feature parity map

The MVP covers document management, permissions and search; lists, pages and workflows arrive in v1; long-tail SharePoint features come later or are replaced by integrations.

| SharePoint capability | Our equivalent | Release | Notes |
| --- | --- | --- | --- |
| Document libraries, versioning, check-in/out | Core DMS | MVP | Major/minor versions, drafts, locks |
| Metadata, content types, managed metadata (term store) | Schema service + taxonomy | MVP (basic), v1 (term store) | Import term sets from SharePoint |
| Permissions, SharePoint groups, inheritance | ACL engine | MVP | Must mirror SharePoint semantics for clean migration |
| AD / LDAP / Kerberos, SAML, OIDC | Identity gateway (Keycloak) | MVP | Kerberos SSO is a must for intranets |
| Office online editing | WOPI host + Collabora or OnlyOffice | MVP | Co-authoring in browser |
| Desktop Office open/save, Explorer view | WebDAV + ms-word: URI handlers | MVP | Test Office 2019/2021/365 Apps |
| Enterprise search | OpenSearch + Tika/OCR | MVP | Security-trimmed results |
| Team sites, pages, web parts | Site + page builder | v1 | Modern-style blocks only |
| Lists, views, list forms | List engine | v1 | Calculated columns, lookups, view filters |
| Workflows (SPD 2010/2013, approvals) | BPMN engine (Flowable or Camunda 7 CE fork) | v1 | Visual designer, approval templates |
| InfoPath forms | Form builder + converter | v1 | Converter handles common forms only |
| Retention, records, eDiscovery, holds | Compliance module | v1.5 | Needed for regulated buyers |
| Hubs, audiences, news | Intranet module | v1.5 |  |
| Sync client (OneDrive-style) | Desktop sync app | v2 | Or partner with Nextcloud client protocol |
| REST / CSOM / Graph-like API | Public REST API + SharePoint REST compatibility layer | v1 (native), v2 (compat) | Compat layer keeps old scripts working |
| Power Automate, Teams, Outlook integration | Connectors | v2 | Outlook add-in for save-to-library |
| BCS, Excel/Visio Services, PerformancePoint | Not built | — | Point to BI tools |

## Architecture and technology stack

A modular monolith on Kubernetes (or plain Docker Compose for small sites), backed by PostgreSQL and S3-compatible storage, with search and editing as separate services.

Why a modular monolith first: one deployable is far easier for customer IT teams to run than 15 microservices, and modules can be split out later where scale demands it.

| Layer | Choice | Reason |
| --- | --- | --- |
| Backend | Java 21 + Spring Boot, or .NET 8 on Linux | .NET eases hiring of ex-SharePoint devs; Java has the richest ECM/BPMN ecosystem |
| Frontend | React + TypeScript, design system with Fluent-like familiarity | Lower retraining cost for SharePoint users |
| Metadata DB | PostgreSQL 16+ (with row-level security) | Proven, free, HA via Patroni |
| File storage | S3 API (MinIO, Ceph, NetApp, Dell ECS) or NFS | Customers reuse existing storage |
| Search | OpenSearch + Apache Tika + Tesseract OCR | Security-trimmed, full-text, facets |
| Office editing | WOPI protocol; Collabora Online or OnlyOffice | Browser co-authoring without Microsoft cloud |
| Desktop access | WebDAV + MS-OFBA/Office URI schemes | Open from Word, map as drive |
| Identity | Keycloak (AD/LDAP, Kerberos, SAML, OIDC, MFA) | Standard, auditable |
| Workflow | Embedded BPMN engine + low-code designer | Replaces SPD and InfoPath logic |
| Async jobs | Message queue (RabbitMQ or NATS) | Indexing, thumbnails, migration jobs |
| Packaging | Helm chart, OCI images, offline bundle | Air-gapped installs |
| Observability | OpenTelemetry, Prometheus, Grafana | Ships with dashboards |

**Key design rules**

- Every object (site, library, folder, item, file) has a stable ID plus a SharePoint-style URL path, so migrated links resolve.
- The permission check lives in one service and is enforced in API, search and WebDAV alike.
- Multi-tenant inside one install (many site collections), single-tenant per customer.
- All features reachable through the public REST API; the UI uses only that API.

## Migration and compatibility strategy

Migration tooling is the product's sales engine: a customer must be able to move a 2 TB farm with permissions, versions and metadata intact over a weekend.

**Migration tool (build from month 3)**

1. **Assess:** scan a farm via REST/CSOM or directly from content databases (read-only SQL). Report sites, sizes, custom solutions, InfoPath forms, workflows and broken inheritance.
2. **Map:** map content types, columns, term sets, users and groups (AD SIDs → directory IDs).
3. **Move:** copy files with all versions, author/editor, created/modified dates and permissions. Resumable, parallel, throttled.
4. **Delta sync:** repeat runs copy only changes, so cut-over takes hours, not days.
5. **Redirect:** a URL redirect service maps old `/sites/x/Shared Documents/...` paths to new locations.
6. **Verify:** item counts, checksums and a permissions diff report per site.

**Compatibility layers**

- SharePoint REST `_api/web/...` subset (lists, items, files) so PowerShell scripts and simple integrations keep working.
- WebDAV with Office's FrontPage-style lock behaviour for desktop Office.
- Converters: SPD 2010/2013 workflows → BPMN templates; InfoPath XSN → native forms (common patterns only, with a report of what needs manual rework).

**Not migrated automatically:** farm solutions, custom web parts, sandboxed code. The assessment flags them and partners rebuild them.

## Phased roadmap

The roadmap runs 24 months in five phases, with three gates that must be passed before the next phase gets full funding.

&#91;embedded content: roadmap · 5 phases, 3 release gates\]

Each gate also requires a passed external pen test and a successful migration of at least one real design-partner farm; a missed gate shifts the following phases rather than cutting quality.

## Team structure and budget estimate

Plan for about €5–6M over 24 months, growing from 12 to roughly 35 people. Figures assume an EU (Central/Eastern Europe) team at a blended fully loaded cost of about €75k per person per year; adjust for your location.

| Team | Months 0–9 (MVP) | Months 10–24 (v1–v2) | Owns |
| --- | --- | --- | --- |
| Core platform (DMS, ACL, storage) | 4 | 6 | Libraries, versions, permissions, API |
| Frontend / UX | 2 | 5 | Web app, page builder, design system |
| Search and content processing | 1 | 3 | OpenSearch, OCR, previews |
| Office integration (WOPI, WebDAV) | 1 | 2 | Editing, desktop Office, Explorer |
| Migration tooling | 1 | 4 | Assessment, copy engine, converters |
| Workflow and forms | 0 | 4 | BPMN, designer, InfoPath converter |
| DevOps / packaging / QA | 2 | 5 | Helm, offline installer, test farms, CI |
| Security engineer | 1 | 2 | Threat model, pen tests, certifications |
| Product + tech lead | 1 + 1 | 2 + 2 | Roadmap, architecture |
| **Total** | **\~14** | **\~35** |  |

**Other costs (24 months):** test SharePoint farms and licences for migration testing, about €150k; external pen tests and ISO 27001 / SOC 2 audit, about €200k; pre-sales and partner enablement, about €600k.

Hire at least two engineers who have built or administered large SharePoint farms. Their knowledge of permission quirks and migration edge cases saves months.

## Security, compliance and operations

Security is a selling point against SharePoint, which keeps facing actively exploited on-prem flaws; the product must ship with a credible security story from day one.

**Security**

- Secure SDLC: threat modelling per module, SAST/DAST and dependency scanning in CI, signed images and SBOMs.
- Encryption in transit (TLS 1.3) and at rest (storage-level plus optional per-library keys via KMS/HSM).
- Full audit log of every read, write, share and permission change; export to SIEM (syslog, Splunk, Elastic).
- External pen test before MVP launch and before every major release; public security advisory process and a bug bounty from v1.

**Compliance targets**

- GDPR tooling (subject access, deletion, data residency) at MVP.
- ISO 27001 for the company by month 18; SOC 2 Type II by month 24.
- NIS2 alignment documentation for EU customers; later BSI C5 / national accreditations where buyers need them.

**Operations**

- Install in under one hour from an offline bundle; zero-downtime rolling upgrades.
- HA reference architecture: 3 app nodes, Patroni PostgreSQL cluster, replicated object storage.
- Backup and point-in-time restore for metadata plus storage snapshots; tested restore of a single document, site or whole install.
- Release cadence: monthly feature releases, security patches within 72 hours of a critical finding, LTS release every 12 months with 3 years of support.

## Go-to-market, risks and success metrics

Sell through SharePoint migration partners and system integrators, priced per user per year below SharePoint SE plus CALs, with a free tier for up to 50 users.

**Go-to-market**

- Design partners: sign 3–5 organizations still on SharePoint 2013/2016/2019 before MVP; give them free licences in exchange for real farms to migrate.
- Partner channel: Microsoft-practice integrators who are losing on-prem SharePoint work; give them migration services margin.
- Open-core option: open-source the core DMS to build trust and community; keep migration, compliance, HA and support commercial.
- Competitors to position against: SharePoint SE, Nextcloud, Alfresco/Hyland, OpenText, M-Files, Nuxeo.

**Risks and mitigations**

| Risk | Impact | Mitigation |
| --- | --- | --- |
| Office desktop integration breaks with Microsoft updates | High | Automated test lab across Office versions; WOPI editors as fallback |
| Migration fidelity gaps (permissions, versions, custom columns) | High | Design partners' real farms as test corpus from month 3 |
| Customers depend on custom farm solutions | Medium | Assessment report early in sales; partner rebuild services |
| Scope creep toward full SharePoint parity | High | Parity map is the contract; every new feature needs a paying customer |
| Long enterprise sales cycles (9–18 months) | Medium | Target urgent post-EOL buyers; pilot in 30 days |
| Security incident in our product | High | Secure SDLC, audits, fast patch SLA |

**Success metrics**

- MVP (month 9): 3 design partners in production; 1 TB migrated with 99.9% item fidelity; install under 1 hour.
- v1 (month 15): 10 paying customers; 25,000 licensed users; search p95 under 500 ms on 10M documents.
- v2 (month 24): 40 paying customers; €3M ARR; 5 certified partners.

**Sources**

- [SharePoint 2016 & 2019 end of life and no ESU — endoflife.ai](https://endoflife.ai/article-sharepoint-2016-2019-eol)
- [Final SharePoint 2016/2019 patch and post-EOL exploitation — endoflife.ai](https://endoflife.ai/article-sharepoint-post-eol-exploitation)
- [Subscription Edition licensing — Stefan Gossner](https://blog.stefan-gossner.com/2023/06/29/important-considerations-for-an-upgrade-from-sharepoint-server-2016-to-2019-or-subscription-edition/)
- [SharePoint end-of-life timeline (InfoPath, SPD 2013) — Barn2](https://barn2.com/blog/sharepoint-end-of-life-timeline-migration-options/)
