import { useQuery } from "@tanstack/react-query";
import { api } from "../api/client";

export interface OfficeCapabilities {
  enabled: boolean;
  available: boolean;
  edit: string[];
  view: string[];
}

export interface OfficeSession {
  actionUrl: string;
  accessToken: string;
  accessTokenTtl: number;
  canWrite: boolean;
  fileName: string;
}

/** Which formats the browser editor supports (cached; re-checked every few minutes). */
export function useOffice() {
  const q = useQuery({
    queryKey: ["office"],
    queryFn: () => api<OfficeCapabilities>("/api/v1/office"),
    staleTime: 5 * 60_000,
  });
  const caps = q.data;
  const ext = (name: string) => name.split(".").pop()?.toLowerCase() ?? "";
  return {
    canOpen: (name: string) => !!caps?.available && (caps.edit.includes(ext(name)) || caps.view.includes(ext(name))),
    canEdit: (name: string) => !!caps?.available && caps.edit.includes(ext(name)),
  };
}

export const officeRoute = (id: string) => `/office/${id}`;

const DESKTOP_SCHEMES: Record<string, string> = {
  doc: "ms-word", docx: "ms-word", docm: "ms-word", dot: "ms-word", dotx: "ms-word", rtf: "ms-word", odt: "ms-word",
  xls: "ms-excel", xlsx: "ms-excel", xlsm: "ms-excel", xlsb: "ms-excel", ods: "ms-excel",
  ppt: "ms-powerpoint", pptx: "ms-powerpoint", pptm: "ms-powerpoint", ppsx: "ms-powerpoint", odp: "ms-powerpoint",
  vsd: "ms-visio", vsdx: "ms-visio",
};

const APP_NAMES: Record<string, string> = {
  "ms-word": "Word",
  "ms-excel": "Excel",
  "ms-powerpoint": "PowerPoint",
  "ms-visio": "Visio",
};

/** WebDAV address of a node, e.g. http://host/dav/sites/hr/Shared%20Documents/a.docx */
export function davUrl(path: string): string {
  return window.location.origin + "/dav" + path.split("/").map(encodeURIComponent).join("/");
}

/**
 * Office URI scheme link that opens the file in the desktop app, which then loads, locks and saves it over
 * WebDAV (`ofe` = open for edit, `ofv` = open for view).
 */
export function desktopLink(name: string, path: string, edit: boolean): { href: string; app: string } | null {
  const scheme = DESKTOP_SCHEMES[name.split(".").pop()?.toLowerCase() ?? ""];
  if (!scheme) return null;
  return { href: `${scheme}:${edit ? "ofe" : "ofv"}|u|${davUrl(path)}`, app: APP_NAMES[scheme] };
}
