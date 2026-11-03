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
}

export type ColumnType = "TEXT" | "NOTE" | "NUMBER" | "DATE" | "BOOLEAN" | "CHOICE" | "URL" | "PERSON";

export interface ColumnDto {
  id: string;
  internalName: string;
  displayName: string;
  type: ColumnType;
  required: boolean;
  choices: string[];
}

export interface AclEntry {
  principal: PrincipalRef;
  level: PermissionLevel;
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
  url: string;
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
