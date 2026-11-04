import { Button, Dropdown, Input, Option, Tooltip, makeStyles, tokens } from "@fluentui/react-components";
import { AddRegular, ArrowDownRegular, ArrowUpRegular, DismissRegular, TableSimpleRegular } from "@fluentui/react-icons";
import type { Link, LinkGroup, NodeDto } from "../api/types";

const MAX_COLUMNS = 6;
const MAX_MENU_LINKS = 12;

const useStyles = makeStyles({
  row: { display: "flex", gap: "6px", alignItems: "center", marginBottom: "6px", flexWrap: "wrap" },
  label: { width: "170px", "@media (max-width: 560px)": { width: "100%" } },
  url: { flex: 1, minWidth: "180px" },
  description: { width: "100%", marginBottom: "10px" },
  actions: { display: "flex", gap: "6px", flexWrap: "wrap", marginTop: "4px" },
  menu: {
    display: "flex", flexDirection: "column", gap: "10px", margin: "2px 0 12px", paddingLeft: "12px",
    borderLeft: `2px solid ${tokens.colorBrandStroke1}`,
  },
  column: { display: "flex", flexDirection: "column", gap: "6px" },
  columnHead: { display: "flex", gap: "6px", alignItems: "center" },
});

/** The columns of a mega menu under a navigation entry. */
function MenuColumns(p: { groups: LinkGroup[]; onChange: (groups: LinkGroup[]) => void; siteContent: NodeDto[]; label: string }) {
  const s = useStyles();
  const set = (i: number, patch: Partial<LinkGroup>) => p.onChange(p.groups.map((g, j) => (j === i ? { ...g, ...patch } : g)));
  return (
    <div className={s.menu} role="group" aria-label={`Menu of ${p.label || "this link"}`}>
      {p.groups.map((g, i) => (
        <div key={i} className={s.column}>
          <div className={s.columnHead}>
            <Input value={g.heading} placeholder={`Column ${i + 1} heading`} maxLength={60} style={{ flex: 1 }}
              onChange={(_, d) => set(i, { heading: d.value })} aria-label="Column heading" />
            <Tooltip content="Remove column" relationship="label">
              <Button appearance="subtle" icon={<DismissRegular />} onClick={() => p.onChange(p.groups.filter((_, j) => j !== i))} />
            </Tooltip>
          </div>
          <LinkEditor links={g.links} max={MAX_MENU_LINKS} siteContent={p.siteContent} onChange={(links) => set(i, { links })} />
        </div>
      ))}
      <div>
        <Button size="small" icon={<AddRegular />} disabled={p.groups.length >= MAX_COLUMNS}
          onClick={() => p.onChange([...p.groups, { heading: "", links: [] }])}>
          Add column
        </Button>
      </div>
    </div>
  );
}

/** Editable, reorderable list of links, with a picker for the site's own libraries and lists. */
export function LinkEditor(props: {
  links: Link[];
  onChange: (links: Link[]) => void;
  siteContent: NodeDto[];
  max: number;
  /** navigation entries can open a mega menu */
  menus?: boolean;
  /** each link also has a line of description */
  descriptions?: boolean;
}) {
  const s = useStyles();
  const { links, onChange } = props;
  const set = (i: number, patch: Partial<Link>) => onChange(links.map((l, j) => (j === i ? { ...l, ...patch } : l)));
  const move = (i: number, by: number) => {
    const next = [...links];
    const [item] = next.splice(i, 1);
    next.splice(i + by, 0, item);
    onChange(next);
  };

  return (
    <div>
      {links.map((l, i) => (
        <div key={i}>
        <div className={s.row}>
          <Input className={s.label} value={l.label} placeholder="Label" maxLength={60}
            onChange={(_, d) => set(i, { label: d.value })} aria-label="Link label" />
          <Input className={s.url} value={l.url ?? ""} placeholder={l.groups?.length ? "Optional address" : "/sites/... or https://..."}
            onChange={(_, d) => set(i, { url: d.value })} aria-label="Link address" />
          {props.menus && (
            <Tooltip content={l.groups ? "Turn the menu off" : "Open a mega menu from this link"} relationship="label">
              <Button appearance={l.groups ? "primary" : "subtle"} icon={<TableSimpleRegular />} aria-pressed={!!l.groups}
                onClick={() => set(i, { groups: l.groups ? undefined : [{ heading: "", links: [] }] })} />
            </Tooltip>
          )}
          <Tooltip content="Move up" relationship="label">
            <Button appearance="subtle" icon={<ArrowUpRegular />} disabled={i === 0} onClick={() => move(i, -1)} />
          </Tooltip>
          <Tooltip content="Move down" relationship="label">
            <Button appearance="subtle" icon={<ArrowDownRegular />} disabled={i === links.length - 1} onClick={() => move(i, 1)} />
          </Tooltip>
          <Tooltip content="Remove" relationship="label">
            <Button appearance="subtle" icon={<DismissRegular />} onClick={() => onChange(links.filter((_, j) => j !== i))} />
          </Tooltip>
        </div>
        {props.descriptions && (
          <Input className={s.description} value={l.description ?? ""} placeholder="Description (optional)" maxLength={120}
            onChange={(_, d) => set(i, { description: d.value })} aria-label="Link description" />
        )}
        {props.menus && l.groups && (
          <MenuColumns groups={l.groups} label={l.label} siteContent={props.siteContent} onChange={(groups) => set(i, { groups })} />
        )}
        </div>
      ))}
      <div className={s.actions}>
        <Button icon={<AddRegular />} disabled={links.length >= props.max}
          onClick={() => onChange([...links, { label: "", url: "" }])}>
          Add link
        </Button>
        {props.siteContent.length > 0 && (
          <Dropdown
            placeholder="Add a library or list…"
            value=""
            selectedOptions={[]}
            disabled={links.length >= props.max}
            onOptionSelect={(_, d) => {
              const n = props.siteContent.find((c) => c.id === d.optionValue);
              if (n) onChange([...links, { label: n.title ?? n.name, url: n.path }]);
            }}
          >
            {props.siteContent.map((n) => (
              <Option key={n.id} value={n.id} text={n.title ?? n.name}>
                {(n.title ?? n.name) + (n.kind === "LIST" ? " (list)" : " (library)")}
              </Option>
            ))}
          </Dropdown>
        )}
      </div>
    </div>
  );
}
