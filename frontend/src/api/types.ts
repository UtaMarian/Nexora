// Mirrors the REST API DTOs (backend: io.nexora.api.Dtos).

export type Kind = "SITE" | "LIBRARY" | "FOLDER" | "FILE" | "LIST" | "LIST_ITEM";
export type PermissionLevel = "READ" | "CONTRIBUTE" | "EDIT" | "FULL_CONTROL";
export type Right =
  | "VIEW"
  | "ADD_ITEMS"
  | "EDIT_ITEMS"
  | "DELETE_ITEMS"
  | "MANAGE_LISTS"
  | "MANAGE_PERMISSIONS"
  | "MANAGE_WEB"
  | "OVERRIDE_CHECKOUT";

export interface PrincipalRef {
  id: string;
  kind: "USER" | "GROUP";
  login: string;
  displayName: string;
}

export interface Me {
  id: string;
  login: string;
  displayName: string;
  email?: string;
  admin: boolean;
  searchEnabled: boolean;
  /** reading without signing in */
  anonymous: boolean;
}

export interface NodeDto {
  id: string;
  kind: Kind;
  name: string;
  title?: string;
  description?: string;
  path: string;
  parentId?: string;
  siteId: string;
  sizeBytes?: number;
  mimeType?: string;
  version?: string;
  checkedOutBy?: PrincipalRef;
  checkedOutAt?: string;
  hasDraft: boolean;
  lockedBy?: PrincipalRef;
  lockExpiresAt?: string;
  createdBy: PrincipalRef;
  createdAt: string;
  modifiedBy: PrincipalRef;
  modifiedAt: string;
  metadata: Record<string, unknown>;
  inheritsPermissions: boolean;
  myLevel?: PermissionLevel;
  myRights: Right[];
  /** The latest approval or review, as KIND:STATUS. */
  approvalStatus?: string;
}

export interface Crumb {
  id: string;
  kind: Kind;
  name: string;
  title?: string;
  path: string;
}

export interface NodeDetail {
  node: NodeDto;
  breadcrumbs: Crumb[];
}

export interface VersionDto {
  id: string;
  label: string;
  major: boolean;
  current: boolean;
  sizeBytes: number;
  sha256: string;
  comment?: string;
  createdBy: PrincipalRef;
  createdAt: string;
}

export interface LibrarySettings {
  versioningEnabled: boolean;
  minorVersionsEnabled: boolean;
  requireCheckout: boolean;
  maxVersions?: number | null;
  draftVisibility?: "READERS" | "EDITORS";
}

export type ColumnType = "TEXT" | "NOTE" | "NUMBER" | "DATE" | "BOOLEAN" | "CHOICE" | "URL" | "PERSON" | "LOOKUP";

export interface ColumnDto {
  id: string;
  internalName: string;
  displayName: string;
  type: ColumnType;
  required: boolean;
  choices: string[];
  lookupListId?: string;
}

export interface AclEntry {
  principal: PrincipalRef;
  level: PermissionLevel;
}

export interface AnonymousStatus {
  /** the farm allows it */
  available: boolean;
  enabled: boolean;
  /** it comes from sourcePath, and is changed there */
  inherited: boolean;
  sourcePath: string;
}

export interface Permissions {
  inheritsPermissions: boolean;
  sourceId: string;
  sourcePath: string;
  entries: AclEntry[];
}

export interface RecycleBinItem {
  id: string;
  kind: Kind;
  name: string;
  path: string;
  siteId: string;
  deletedBy?: PrincipalRef;
  deletedAt: string;
}

export interface AuditEvent {
  id: string;
  occurredAt: string;
  actor?: string;
  action: string;
  nodeId?: string;
  path?: string;
  details?: Record<string, unknown>;
}

export interface Page<T> {
  items: T[];
  page: number;
  size: number;
  total: number;
}

export interface Link {
  label: string;
  /** optional when the link opens a mega menu */
  url?: string;
  description?: string;
  groups?: LinkGroup[];
}

/** One column of a mega menu. */
export interface LinkGroup {
  heading: string;
  links: Link[];
}

/** Someone who cannot open the hub gets only its theme. */
export interface Hub {
  id?: string;
  title?: string;
  path?: string;
  navigation: Link[];
  theme?: string;
}

export interface SiteRef {
  id: string;
  title: string;
  path: string;
}

export interface SiteDesign {
  theme: string;
  header: { layout: "compact" | "standard" | "extended"; background: "none" | "soft" | "strong"; showTitle: boolean; showLogo: boolean };
  navigation: Link[];
  home: {
    welcomeTitle: string;
    welcome: string;
    quickLinks: Link[];
    showNews: boolean;
    newsListId?: string | null;
    showLibraries: boolean;
    showLists: boolean;
  };
}

export interface DesignResponse {
  design: SiteDesign;
  logoUrl?: string;
  template?: string;
  /** a published modern page shown as the site's home page */
  homePageId?: string;
  parent?: SiteRef;
  /** the hub this site belongs to: itself, the one it joined, or its parent's */
  hub?: Hub;
  isHub: boolean;
  joinedHubId?: string;
}

// ---- modern pages (backend: io.nexora.pages) ----

export type PageKind = "PAGE" | "NEWS";

export type ListViewType = "table" | "calendar" | "gallery";

export interface ListViewFilter {
  column: string;
  operator: "eq" | "ne" | "contains" | "gt" | "lt" | "empty" | "notEmpty";
  value?: string;
}

export interface ListView {
  id: string;
  name: string;
  type: ListViewType;
  columns: string[];
  sortBy?: string | null;
  sortDescending: boolean;
  filters: ListViewFilter[];
  dateColumn?: string | null;
  endColumn?: string | null;
  isDefault: boolean;
}

export interface ItemVersion {
  version: number;
  current: boolean;
  title: string;
  values?: Record<string, unknown>;
  createdBy?: PrincipalRef;
  createdAt: string;
}

export type AlertFrequency = "IMMEDIATE" | "DAILY" | "WEEKLY";

export interface AlertDto {
  id: string;
  nodeId: string;
  title: string;
  path: string;
  kind: Kind;
  frequency: AlertFrequency;
  createdAt: string;
}

export interface ShareLinkDto {
  id: string;
  scope: "ANYONE" | "ORGANIZATION";
  expiresAt: string;
  hasPassword: boolean;
  useCount: number;
  lastUsedAt?: string;
  createdBy: PrincipalRef;
  createdAt: string;
  path?: string;
}

export interface SharesDto {
  links: ShareLinkDto[];
  people: { id: string; principal: PrincipalRef; level: string; sharedBy: PrincipalRef; sharedAt: string }[];
  anyoneLinksAllowed: boolean;
  maxLinkDays: number;
  emailEnabled: boolean;
}

export interface SearchHit {
  id: string;
  kind: Kind | PageKind;
  title: string;
  name: string;
  path: string;
  siteTitle?: string;
  /** Pieces of text; the ones with match set are the words that were found. Never contains HTML. */
  snippet: { text: string; match: boolean }[];
  modifiedAt: string;
  modifiedBy?: string;
  sizeBytes?: number;
  mimeType?: string;
}

export interface SearchFacetValue {
  value: string;
  label: string;
  count: number;
}

export interface SearchResponse {
  total: number;
  hits: SearchHit[];
  facets: Record<string, SearchFacetValue[]>;
  /** The search engine is down: these are name matches only. */
  degraded: boolean;
}
export type SectionLayout = "one" | "two" | "three" | "oneThirdLeft" | "oneThirdRight";
export type SectionBackground = "none" | "soft" | "strong";
export type WebPartType = "text" | "image" | "documents" | "list" | "news" | "quicklinks" | "events" | "people" | "embed";

export interface WebPart {
  id: string;
  type: WebPartType;
  props: Record<string, unknown>;
}

export interface Section {
  layout: SectionLayout;
  background: SectionBackground;
  columns: WebPart[][];
}

export interface Canvas {
  sections: Section[];
}

export interface PageContent {
  title: string;
  description?: string;
  bannerImageId?: string;
  canvas: Canvas;
}

export interface PageTemplateInfo {
  /** a built-in layout's name, or a saved template's page id */
  id: string;
  title: string;
  description?: string;
  custom: boolean;
}

export interface PageSummary {
  id: string;
  siteTitle?: string;
  siteId: string;
  name: string;
  kind: PageKind;
  title: string;
  description?: string;
  url: string;
  bannerImageId?: string;
  excerpt?: string;
  published: boolean;
  hasDraft: boolean;
  version: number;
  author?: PrincipalRef;
  publishedAt?: string;
  modifiedBy?: PrincipalRef;
  modifiedAt: string;
  home: boolean;
}

export interface PageDetail {
  page: PageSummary;
  published?: PageContent;
  draft?: PageContent;
  revision: number;
  canEdit: boolean;
  canDelete: boolean;
  canSetHome: boolean;
}

export interface PageVersion {
  version: number;
  title: string;
  publishedBy?: PrincipalRef;
  publishedAt: string;
}

export interface TemplateInfo {
  id: string;
  title: string;
  description: string;
}

export interface Templates {
  sites: TemplateInfo[];
  lists: TemplateInfo[];
}

export interface PersonValue {
  id: string;
  login: string;
  displayName: string;
}
