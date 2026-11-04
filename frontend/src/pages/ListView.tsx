import { Fragment, useMemo, useState } from "react";
import { useNavigate } from "react-router-dom";
import { useQuery, useQueryClient } from "@tanstack/react-query";
import {
  Badge,
  Button,
  Card,
  Input,
  Menu,
  MenuItem,
  MenuList,
  MenuPopover,
  MenuTrigger,
  Tab,
  TabList,
  Table,
  TableBody,
  TableCell,
  TableHeader,
  TableHeaderCell,
  TableRow,
  Text,
  Toolbar,
  ToolbarButton,
  makeStyles,
  tokens,
} from "@fluentui/react-components";
import {
  AddRegular,
  AlertRegular,
  CalendarLtrRegular,
  FlashRegular,
  DeleteRegular,
  EditRegular,
  FilterRegular,
  GridRegular,
  MoreHorizontalRegular,
  PeopleRegular,
  SettingsRegular,
} from "@fluentui/react-icons";
import { api, routeFor } from "../api/client";
import type { ColumnDto, Crumb, ListView as SavedView, NodeDto } from "../api/types";
import { Crumbs } from "../components/Crumbs";
import { can } from "../components/me";
import { ColumnValue, valueText } from "../components/columns";
import { AlertDialog } from "../dialogs/AlertDialog";
import { RulesDialog } from "../workflow/RulesDialog";
import { approvalBadge } from "../workflow/types";
import { BUILT_IN, CalendarView, GalleryView, ViewDialog, applyView, cellValue } from "../lists/ListViews";
import { Empty, ErrorBar, Loading } from "../components/States";
import { formatRelative } from "../components/format";
import { ItemDialog } from "../dialogs/ItemDialog";
import { LibrarySettingsDialog } from "../dialogs/LibrarySettingsDialog";
import { PermissionsDialog } from "../dialogs/PermissionsDialog";

const useStyles = makeStyles({
  head: { display: "flex", flexDirection: "column", gap: "8px", marginBottom: "8px" },
  bar: { display: "flex", alignItems: "center", gap: "8px", flexWrap: "wrap" },
  card: { padding: 0, overflow: "hidden" },
  scroll: { overflowX: "auto" },
  row: { cursor: "pointer" },
  sortable: { cursor: "pointer", userSelect: "none", whiteSpace: "nowrap" },
  muted: { color: tokens.colorNeutralForeground3 },
});

type Sort = { key: string; dir: 1 | -1 };

/** A SharePoint-style list: sortable, filterable items with a form per item. */
export function ListView(props: { list: NodeDto; breadcrumbs: Crumb[]; openItemId?: string }) {
  const { list, breadcrumbs } = props;
  const s = useStyles();
  const qc = useQueryClient();
  const navigate = useNavigate();
  const items = useQuery({ queryKey: ["children", list.id], queryFn: () => api<NodeDto[]>(`/api/v1/nodes/${list.id}/children`) });
  const columns = useQuery({ queryKey: ["columns", list.id], queryFn: () => api<ColumnDto[]>(`/api/v1/nodes/${list.id}/columns`) });

  const views = useQuery({ queryKey: ["views", list.id], queryFn: () => api<SavedView[]>(`/api/v1/nodes/${list.id}/views`) });
  const [viewId, setViewId] = useState<string | null>(null);
  const [viewDialog, setViewDialog] = useState<"new" | "edit" | null>(null);
  const view = (views.data ?? []).find((v) => v.id === viewId)
    ?? (viewId === "all" ? undefined : (views.data ?? []).find((v) => v.isDefault));
  const canManage = can(list, "MANAGE_LISTS");
  const [filter, setFilter] = useState("");
  const [sort, setSort] = useState<Sort>({ key: "id", dir: -1 });
  const [sorted, setSorted] = useState(false);
  const [dialog, setDialog] = useState<"new" | "settings" | "permissions" | "alert" | "rules" | null>(null);
  const [editing, setEditing] = useState<NodeDto | null>(null);
  const cols = columns.data ?? [];
  const deepLinked = props.openItemId ? items.data?.find((i) => i.id === props.openItemId) : undefined;

  const rows = useMemo(() => {
    const q = filter.trim().toLowerCase();
    const cell = (n: NodeDto, key: string): string | number => {
      if (key === "id") return Number(n.name);
      if (key === "title") return (n.title ?? "").toLowerCase();
      if (key === "modified") return n.modifiedAt;
      const v = n.metadata?.[key];
      return typeof v === "number" ? v : valueText(v).toLowerCase();
    };
    const searched = (items.data ?? [])
      .filter((n) => !q || [n.title ?? "", ...Object.values(n.metadata ?? {}).map(valueText)].some((t) => t.toLowerCase().includes(q)));
    // a view brings its own order until someone clicks a column
    const ordered = [...searched].sort((a, b) => {
      const x = cell(a, sort.key);
      const y = cell(b, sort.key);
      return (x < y ? -1 : x > y ? 1 : 0) * sort.dir;
    });
    return view && !sorted ? applyView(ordered, view) : applyView(ordered, view ? { ...view, sortBy: null } : null);
  }, [items.data, filter, sort, sorted, view]);

  // the columns a table shows: the view's, or else everything
  const tableColumns = view?.type === "table" && view.columns?.length ? view.columns : ["Title", ...cols.map((c) => c.internalName), "Modified"];

  const header = (key: string, label: string) => (
    <TableHeaderCell
      className={s.sortable}
      onClick={() => {
        setSorted(true);
        setSort((p) => ({ key, dir: p.key === key ? (p.dir === 1 ? -1 : 1) : 1 }));
      }}
      sortDirection={sorted && sort.key === key ? (sort.dir === 1 ? "ascending" : "descending") : undefined}
    >
      {label}
    </TableHeaderCell>
  );

  const remove = async (item: NodeDto) => {
    if (!confirm(`Delete "${item.title}"? It goes to the recycle bin.`)) return;
    await api(`/api/v1/nodes/${item.id}`, { method: "DELETE" });
    await qc.invalidateQueries();
  };

  const closeItem = () => {
    setEditing(null);
    if (props.openItemId) navigate(routeFor(list.path), { replace: true });
  };

  return (
    <>
      <div className={s.head}>
        <Crumbs items={breadcrumbs.slice(0, breadcrumbs.findIndex((c) => c.id === list.id) + 1)} />
        {list.description && <Text className={s.muted}>{list.description}</Text>}
        <div className={s.bar}>
          <Toolbar aria-label={`${list.title} commands`} style={{ paddingLeft: 0, flexWrap: "wrap" }}>
            <ToolbarButton appearance="primary" icon={<AddRegular />} disabled={!can(list, "ADD_ITEMS")} onClick={() => setDialog("new")}>
              New item
            </ToolbarButton>
            <ToolbarButton icon={<AlertRegular />} onClick={() => setDialog("alert")}>Alert me</ToolbarButton>
            {can(list, "MANAGE_PERMISSIONS") && (
              <ToolbarButton icon={<PeopleRegular />} onClick={() => setDialog("permissions")}>
                Manage access
              </ToolbarButton>
            )}
            {can(list, "MANAGE_LISTS") && (
              <ToolbarButton icon={<FlashRegular />} onClick={() => setDialog("rules")}>Automate</ToolbarButton>
            )}
            {can(list, "MANAGE_LISTS") && (
              <ToolbarButton icon={<SettingsRegular />} onClick={() => setDialog("settings")}>
                List settings
              </ToolbarButton>
            )}
          </Toolbar>
          <Input
            contentBefore={<FilterRegular />}
            placeholder="Filter items"
            value={filter}
            onChange={(_, d) => setFilter(d.value)}
            style={{ marginLeft: "auto", minWidth: 200 }}
            aria-label="Filter items"
          />
        </div>
      </div>

      <TabList size="small" selectedValue={view?.id ?? "all"} onTabSelect={(_, d) => { setViewId(d.value as string); setSorted(false); }} aria-label="Views" style={{ flexWrap: "wrap", marginBottom: 8 }}>
        <Tab value="all">All items</Tab>
        {(views.data ?? []).map((v) => (
          <Tab key={v.id} value={v.id} icon={v.type === "calendar" ? <CalendarLtrRegular /> : v.type === "gallery" ? <GridRegular /> : undefined}>{v.name}</Tab>
        ))}
        {canManage && <Button appearance="subtle" size="small" icon={<AddRegular />} onClick={() => setViewDialog("new")}>New view</Button>}
        {canManage && view && <Button appearance="subtle" size="small" icon={<EditRegular />} onClick={() => setViewDialog("edit")}>Edit view</Button>}
      </TabList>

      <ErrorBar error={items.error ?? columns.error} />
      <Card className={s.card}>
        {items.isPending || columns.isPending ? (
          <Loading />
        ) : view?.type === "calendar" ? (
          <CalendarView items={rows} view={view} onOpen={setEditing} />
        ) : !rows.length ? (
          <Empty title={filter ? "No items match the filter" : "This list is empty"}>
            {!filter && can(list, "ADD_ITEMS") && <Text>Use New item to add the first one.</Text>}
          </Empty>
        ) : view?.type === "gallery" ? (
          <div style={{ padding: 12 }}>
            <GalleryView list={list} items={rows} columns={cols} view={view} onOpen={setEditing} />
          </div>
        ) : (
          <div className={s.scroll}>
            <Table aria-label={`Items in ${list.title}`} style={{ minWidth: 300 + tableColumns.length * 120 }}>
              <TableHeader>
                <TableRow>
                  {header("id", "ID")}
                  {tableColumns.map((key) => {
                    const c = cols.find((x) => x.internalName === key);
                    return <Fragment key={key}>{header(key === "Title" ? "title" : key === "Modified" ? "modified" : key, c?.displayName ?? BUILT_IN.find((b) => b.key === key)?.label ?? key)}</Fragment>;
                  })}
                  <TableHeaderCell style={{ width: 48 }} />
                </TableRow>
              </TableHeader>
              <TableBody>
                {rows.map((n) => (
                  <TableRow key={n.id} className={s.row} onClick={() => setEditing(n)}>
                    <TableCell style={{ width: 56 }}>{n.name}</TableCell>
                    {tableColumns.map((key) => {
                      const c = cols.find((x) => x.internalName === key);
                      if (c) return <TableCell key={key}><ColumnValue column={c} value={n.metadata?.[key]} /></TableCell>;
                      if (key === "Title") {
                        const badge = approvalBadge(n.approvalStatus);
                        return (
                          <TableCell key={key}>
                            <Text weight="semibold">{n.title}</Text>
                            {badge && <Badge appearance="tint" size="small" color={badge.color} style={{ marginLeft: 8 }}>{badge.label}</Badge>}
                          </TableCell>
                        );
                      }
                      if (key === "Modified") return <TableCell key={key} title={n.modifiedBy?.displayName}>{formatRelative(n.modifiedAt)}</TableCell>;
                      if (key === "Created") return <TableCell key={key}>{formatRelative(n.createdAt)}</TableCell>;
                      return <TableCell key={key}>{cellValue(n, key)}</TableCell>;
                    })}
                    <TableCell onClick={(e) => e.stopPropagation()}>
                      <Menu positioning="below-end">
                        <MenuTrigger disableButtonEnhancement>
                          <Button appearance="subtle" size="small" icon={<MoreHorizontalRegular />} aria-label={`Actions for ${n.title}`} />
                        </MenuTrigger>
                        <MenuPopover>
                          <MenuList>
                            <MenuItem icon={<EditRegular />} onClick={() => setEditing(n)}>
                              {can(n, "EDIT_ITEMS") ? "Edit" : "View"}
                            </MenuItem>
                            {can(n, "DELETE_ITEMS") && (
                              <MenuItem icon={<DeleteRegular />} onClick={() => remove(n)}>
                                Delete
                              </MenuItem>
                            )}
                          </MenuList>
                        </MenuPopover>
                      </Menu>
                    </TableCell>
                  </TableRow>
                ))}
              </TableBody>
            </Table>
          </div>
        )}
      </Card>

      {dialog === "new" && <ItemDialog list={list} columns={cols} onClose={() => setDialog(null)} />}
      {(editing ?? deepLinked) && <ItemDialog list={list} columns={cols} item={(editing ?? deepLinked)!} onClose={closeItem} />}
      {dialog === "settings" && <LibrarySettingsDialog library={list} onClose={() => setDialog(null)} />}
      {dialog === "permissions" && <PermissionsDialog node={list} onClose={() => setDialog(null)} />}
      {dialog === "alert" && <AlertDialog node={list} onClose={() => setDialog(null)} />}
      {dialog === "rules" && <RulesDialog container={list} columns={cols} onClose={() => setDialog(null)} />}
      {viewDialog && (
        <ViewDialog list={list} columns={cols} view={viewDialog === "edit" ? view : undefined}
          onSaved={(v) => { setViewId(v.id); setSorted(false); setViewDialog(null); }}
          onDeleted={() => { setViewId("all"); setViewDialog(null); }}
          onClose={() => setViewDialog(null)} />
      )}
    </>
  );
}

