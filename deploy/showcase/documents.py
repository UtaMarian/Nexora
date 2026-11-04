"""Writes the showcase site's Office files and the HTML its PDFs are printed from.

    python documents.py <images folder> <output folder>      (needs python-docx, openpyxl, python-pptx)

Kestrelwind Energy and everyone in these files are invented.
"""
import sys
from pathlib import Path

from docx import Document
from docx.enum.text import WD_ALIGN_PARAGRAPH
from docx.shared import Pt, RGBColor
from openpyxl import Workbook
from openpyxl.chart import BarChart, LineChart, Reference
from openpyxl.styles import Alignment, Font, PatternFill
from pptx import Presentation
from pptx.dml.color import RGBColor as PptColor
from pptx.enum.shapes import MSO_SHAPE
from pptx.util import Inches, Pt as PptPt

IMAGES, OUT = Path(sys.argv[1]), Path(sys.argv[2])
TEAL = RGBColor(0x0B, 0x4F, 0x6C)


def docx(path, title, subtitle, sections, owner="Policy owner: Compliance", version="Version 3.0, approved October 2026"):
    d = Document()
    d.core_properties.title = title
    d.core_properties.author = "Kestrelwind Energy"
    d.styles["Normal"].font.name = "Calibri"
    d.styles["Normal"].font.size = Pt(11)
    for name, size in (("Heading 1", 16), ("Heading 2", 13)):
        d.styles[name].font.color.rgb = TEAL
        d.styles[name].font.size = Pt(size)
    d.add_picture(str(IMAGES / "logo.png"), width=Pt(46))
    t = d.add_paragraph()
    run = t.add_run(title)
    run.bold, run.font.size, run.font.color.rgb = True, Pt(26), TEAL
    s = d.add_paragraph()
    s.add_run(subtitle).italic = True
    meta = d.add_paragraph(f"{owner}  |  {version}")
    meta.runs[0].font.size = Pt(9)
    for heading, body in sections:
        d.add_heading(heading, level=1)
        for item in body:
            if item.startswith("- "):
                d.add_paragraph(item[2:], style="List Bullet")
            else:
                d.add_paragraph(item)
    footer = d.sections[0].footer.paragraphs[0]
    footer.text = "Kestrelwind Energy, internal use"
    footer.alignment = WD_ALIGN_PARAGRAPH.CENTER
    path.parent.mkdir(parents=True, exist_ok=True)
    d.save(path)


def xlsx_budget(path):
    wb = Workbook()
    ws = wb.active
    ws.title = "FY27 budget"
    ws["A1"] = "Kestrelwind Energy: FY27 operating budget (EUR million)"
    ws["A1"].font = Font(bold=True, size=14, color="0B4F6C")
    head = ["Division", "Q1", "Q2", "Q3", "Q4", "Total"]
    rows = [("Onshore wind", 41.2, 38.6, 36.4, 44.8), ("Offshore wind", 58.0, 54.3, 51.7, 63.9), ("Solar", 12.4, 18.9, 21.3, 11.2),
            ("Battery storage", 7.1, 7.8, 8.4, 9.2), ("Grid services", 5.5, 5.2, 5.9, 6.3), ("Corporate", -14.6, -14.1, -13.9, -15.2)]
    ws.append([])
    ws.append(head)
    for c in ws[3]:
        c.font = Font(bold=True, color="FFFFFF")
        c.fill = PatternFill("solid", fgColor="0B4F6C")
        c.alignment = Alignment(horizontal="center")
    for i, r in enumerate(rows, start=4):
        ws.append([*r, f"=SUM(B{i}:E{i})"])
    last = 3 + len(rows)
    ws.append(["Group", *[f"=SUM({c}4:{c}{last})" for c in "BCDEF"]])
    for c in ws[last + 1]:
        c.font = Font(bold=True)
    ws.column_dimensions["A"].width = 22
    chart = BarChart()
    chart.title = "Contribution by division"
    chart.height, chart.width = 9, 18
    chart.add_data(Reference(ws, min_col=2, max_col=5, min_row=3, max_row=last), titles_from_data=True)
    chart.set_categories(Reference(ws, min_col=1, min_row=4, max_row=last))
    ws.add_chart(chart, "H3")
    wb.save(path)


def xlsx_generation(path):
    wb = Workbook()
    ws = wb.active
    ws.title = "Generation 2026"
    ws.append(["Month", "Wind GWh", "Solar GWh", "Availability %"])
    for c in ws[1]:
        c.font = Font(bold=True, color="FFFFFF")
        c.fill = PatternFill("solid", fgColor="0F766E")
    data = [("Jan", 612, 41, 96.1), ("Feb", 588, 63, 97.0), ("Mar", 601, 118, 96.8), ("Apr", 512, 171, 97.4), ("May", 468, 214, 98.0),
            ("Jun", 431, 236, 97.9), ("Jul", 402, 248, 98.2), ("Aug", 427, 229, 97.6), ("Sep", 519, 164, 96.9), ("Oct", 604, 102, 97.1)]
    for row in data:
        ws.append(row)
    chart = LineChart()
    chart.title = "Monthly generation"
    chart.height, chart.width = 9, 18
    chart.add_data(Reference(ws, min_col=2, max_col=3, min_row=1, max_row=len(data) + 1), titles_from_data=True)
    chart.set_categories(Reference(ws, min_col=1, min_row=2, max_row=len(data) + 1))
    ws.add_chart(chart, "F2")
    wb.save(path)


def xlsx_expenses(path):
    wb = Workbook()
    ws = wb.active
    ws.title = "Expense claim"
    ws["A1"] = "Expense claim"
    ws["A1"].font = Font(bold=True, size=16, color="0B4F6C")
    for i, label in enumerate(("Employee name", "Cost centre", "Approver"), start=3):
        ws[f"A{i}"] = label
    ws.append([])
    ws.append(["Date", "Description", "Category", "Amount (EUR)"])
    for c in ws[7]:
        c.font = Font(bold=True, color="FFFFFF")
        c.fill = PatternFill("solid", fgColor="0B4F6C")
    ws["C18"] = "Total"
    ws["D18"] = "=SUM(D8:D17)"
    for col, width in zip("ABCD", (16, 40, 18, 16)):
        ws.column_dimensions[col].width = width
    wb.save(path)


def pptx(path, title, subtitle, cover, slides):
    p = Presentation()
    p.slide_width, p.slide_height = Inches(13.33), Inches(7.5)
    blank = p.slide_layouts[6]

    def text(slide, x, y, w, h, value, size, color, bold=False):
        box = slide.shapes.add_textbox(Inches(x), Inches(y), Inches(w), Inches(h))
        box.text_frame.word_wrap = True
        run = box.text_frame.paragraphs[0].add_run()
        run.text = value
        run.font.size, run.font.bold = PptPt(size), bold
        run.font.color.rgb = PptColor.from_string(color)
        return box

    s = p.slides.add_slide(blank)
    s.shapes.add_picture(str(IMAGES / cover), 0, 0, width=p.slide_width, height=p.slide_height)
    band = s.shapes.add_shape(MSO_SHAPE.RECTANGLE, 0, Inches(4.9), p.slide_width, Inches(2.6))
    band.fill.solid()
    band.fill.fore_color.rgb = PptColor(0x07, 0x1A, 0x2E)
    band.line.fill.background()
    text(s, 0.7, 5.1, 12, 1, title, 44, "FFFFFF", True)
    text(s, 0.7, 6.2, 12, 0.7, subtitle, 20, "9FE7D8")
    for heading, bullets, picture in slides:
        s = p.slides.add_slide(blank)
        bar = s.shapes.add_shape(MSO_SHAPE.RECTANGLE, 0, 0, Inches(0.28), p.slide_height)
        bar.fill.solid()
        bar.fill.fore_color.rgb = PptColor(0x0F, 0x76, 0x6E)
        bar.line.fill.background()
        text(s, 0.7, 0.45, 12, 1, heading, 34, "0B4F6C", True)
        box = s.shapes.add_textbox(Inches(0.7), Inches(1.7), Inches(6.2 if picture else 12), Inches(5))
        tf = box.text_frame
        tf.word_wrap = True
        for i, b in enumerate(bullets):
            para = tf.paragraphs[0] if i == 0 else tf.add_paragraph()
            run = para.add_run()
            run.text = "•  " + b
            run.font.size = PptPt(21)
            para.space_after = PptPt(14)
        if picture:
            s.shapes.add_picture(str(IMAGES / picture), Inches(7.2), Inches(1.7), width=Inches(5.6))
    p.save(path)


def main():
    pol = OUT / "policies"
    conduct = [("Our standard", ["Everyone at Kestrelwind Energy acts with honesty, care and respect, for colleagues, for landowners and for the communities around our wind farms and solar parks.", "This code applies to all employees, contractors and board members in every country where we operate."]),
               ("Conflicts of interest", ["Declare any personal interest in a supplier, landowner or competitor to your manager within five working days.", "- Gifts above EUR 50 must be recorded in the gift register", "- Hospitality from bidders during a live tender must be declined"]),
               ("Speaking up", ["If something feels wrong, say so. Use the confidential Speak Up line, and read the whistleblowing procedure. Retaliation against anyone who raises a concern in good faith is a dismissal offence."])]
    docx(pol / "Code of Conduct.docx", "Code of Conduct", "How we work with each other and the world around us", conduct)
    docx(pol / "Health and Safety Policy.docx", "Health and Safety Policy", "Everybody home safe, every day", [
        ("Commitment", ["Nothing we do is so urgent that it cannot be done safely. Every employee and contractor has the authority to stop work.", "Our target is a lost-time injury frequency below 0.5 per million hours worked."]),
        ("Working at height", ["All nacelle and tower work requires a current GWO Working at Heights certificate, a rescue plan and a two-person team.", "- Harnesses are inspected before every climb", "- Climbing stops when mean wind speed exceeds 15 m/s at hub height", "- Lightning within 15 km means immediate evacuation of the turbine"]),
        ("Reporting", ["Report every incident and near miss in the Safety app within 24 hours. Near misses are how we learn; nobody is punished for reporting one."])])
    docx(pol / "Information Security Policy.docx", "Information Security Policy", "Protecting our people, our data and the grid", [
        ("Scope", ["This policy covers every system that holds company data, and all operational technology, including SCADA systems at wind farms, solar parks and battery sites."]),
        ("Rules for everyone", ["- Use multi-factor authentication on every account", "- Lock your screen when you step away", "- Report phishing with the Report button; never forward it", "- Keep customer and landowner data inside NEXORA, never on personal drives"]),
        ("Operational technology", ["SCADA networks are separated from the office network. Remote vendor access goes through the jump host, with a named engineer and a time-limited ticket."])])
    docx(pol / "Flexible Working Policy.docx", "Flexible Working Policy", "Work where you do your best work", [
        ("Principles", ["Office-based colleagues may work from home up to three days a week, agreed with their manager. Site technicians follow the rota for their region."]),
        ("Core hours", ["Teams agree their own core hours. Meetings stay between 10:00 and 16:00 local time wherever possible, and Fridays after 14:00 stay meeting-free."])])
    docx(pol / "Travel and Expenses Policy.docx", "Travel and Expenses Policy", "Travel less, travel cleaner", [
        ("Before you book", ["Ask whether a video call would do. Trains are the default for trips under five hours. Flights under 800 km need director approval."]),
        ("Claiming", ["Submit claims within 30 days using the expense claim form, with receipts attached. Hotels are capped at EUR 160 a night, EUR 220 in capital cities."])])
    docx(pol / "Whistleblowing Procedure.docx", "Whistleblowing Procedure", "How to raise a concern safely", [
        ("How to report", ["Use the Speak Up line, run by an independent provider, or contact the Audit Committee chair directly. You may stay anonymous."]),
        ("What happens next", ["The Compliance team acknowledges within 7 days and gives feedback within 3 months. Your identity is shared only with your consent or where the law requires it."])])

    tpl = OUT / "templates"
    tpl.mkdir(parents=True, exist_ok=True)
    docx(tpl / "Project Charter Template.docx", "Project Charter", "Replace this text with the project name", [
        ("Purpose", ["Why are we doing this project, and what changes when it is finished?"]), ("Scope", ["- In scope:", "- Out of scope:"]),
        ("Milestones", ["List the stage gates, with dates and owners."]), ("Risks", ["Name the top five risks and who owns each one."])], owner="Template owner: Project Office", version="Template v2")
    docx(tpl / "Risk Assessment Template.docx", "Risk Assessment", "Complete before any work on site", [
        ("Task and location", ["Describe the job, the site and the turbine or inverter number."]), ("Hazards", ["For each hazard, record who could be harmed, the controls, and the residual risk."]),
        ("Sign-off", ["Site supervisor, HSE adviser and the person doing the work all sign before work starts."])], owner="Template owner: HSE", version="Template v5")
    docx(tpl / "Board Paper Template.docx", "Board Paper", "Title of the paper", [("Decision requested", ["State in one sentence what the board is asked to decide."]), ("Background", ["Keep it to one page."]), ("Options and recommendation", ["Compare at most three options, and recommend one."])], owner="Template owner: Company Secretary", version="Template v3")
    xlsx_expenses(tpl / "Expense Claim Form.xlsx")
    pptx(tpl / "Company Presentation Template.pptx", "Your title here", "Presenter name, date", "photo-ridge.jpg", [("Section heading", ["First key message", "Second key message", "Third key message"], "photo-solar.jpg")])

    fin = OUT / "documents"
    (fin / "Finance").mkdir(parents=True, exist_ok=True)
    (fin / "Strategy").mkdir(parents=True, exist_ok=True)
    (fin / "Board papers").mkdir(parents=True, exist_ok=True)
    (fin / "Operations").mkdir(parents=True, exist_ok=True)
    xlsx_budget(fin / "Finance" / "FY27 Operating Budget.xlsx")
    xlsx_generation(fin / "Operations" / "Generation Report 2026.xlsx")
    pptx(fin / "Strategy" / "Strategy 2030.pptx", "Strategy 2030", "Twice the clean power, half the wait", "photo-ridge.jpg", [
        ("Where we are", ["2.4 GW operating across 31 sites", "Availability above 97 percent for three years running", "4.1 million tonnes of CO2 avoided every year"], "photo-solar.jpg"),
        ("Where we are going", ["5 GW operating capacity by 2030", "1.5 GW of offshore wind at Northern Reach", "Every wind farm paired with battery storage"], "photo-sea.jpg"),
        ("How we get there", ["Repower the oldest onshore fleet", "Shorten planning with early community partnership", "One digital platform for every site"], "photo-storage.jpg")])
    pptx(fin / "Strategy" / "Q3 Town Hall.pptx", "Q3 Town Hall", "Results, safety and what comes next", "photo-sea.jpg", [
        ("Q3 in numbers", ["Generation up 9 percent on last year", "Northern Reach foundations 60 percent complete", "Zero lost-time injuries for 212 days"], "photo-ridge.jpg"),
        ("Your questions", ["Hybrid working stays", "Graduate programme doubles to 24 places", "Pay review letters arrive in January"], None)])
    docx(fin / "Board papers" / "Project Falcon Acquisition Proposal.docx", "Project Falcon", "Strictly confidential: proposed acquisition of a 400 MW solar portfolio", [
        ("Decision requested", ["The board is asked to approve a non-binding offer of EUR 310 million for the Project Falcon portfolio."]),
        ("Rationale", ["The portfolio adds daytime output that balances our wind fleet, and it brings ready-to-build battery sites."]),
        ("Risks", ["Grid curtailment in the southern zone, and a competing bidder expected in the final round."])], owner="Sponsor: Chief Financial Officer", version="Board draft, November 2026")
    docx(fin / "Board papers" / "CEO Succession Plan.docx", "Executive Succession Plan", "Strictly confidential: board members only", [
        ("Purpose", ["Sets out emergency and planned succession for the executive team, reviewed every year by the Remuneration Committee."])], owner="Sponsor: Chair of the Board", version="October 2026")

    pro = OUT / "projects"
    charters = {
        "Northern Reach Offshore": ("A 1.5 GW offshore wind farm, 38 km from the coast, with 100 turbines of 15 MW on monopile foundations.", "First power is planned for the third quarter of 2028."),
        "Sunfield Hybrid Park": ("A 320 MW solar park with 120 MW of co-located battery storage on former quarry land.", "Construction starts in spring 2027 with a local workforce commitment of 40 percent."),
        "Highmoor Repowering": ("Replaces 42 ageing turbines with 14 modern machines, doubling output from the same ridge.", "Blades from the old fleet go to a certified recycling partner."),
        "GridLink Battery Fleet": ("Eight battery sites totalling 480 MWh that provide frequency response to the grid operator.", "The first two sites are in commissioning."),
    }
    for name, (what, note) in charters.items():
        docx(pro / f"{name} Project Charter.docx", name, "Project charter", [("Purpose", [what]), ("Next milestone", [note]), ("Community", ["A community benefit fund pays EUR 5,000 per MW each year to local projects."])], owner="Project Office", version="Baseline 1.0")

    # pages the PDF step prints
    html = OUT / "html"
    html.mkdir(parents=True, exist_ok=True)
    style = """<style>@page{size:A4;margin:0}body{font-family:Segoe UI,Arial,sans-serif;color:#17303c;margin:0;font-size:11.5pt;line-height:1.55}
    .cover{height:297mm;background:#071a2e;color:#fff;display:flex;flex-direction:column;justify-content:flex-end;page-break-after:always;background-size:cover;background-position:center}
    .cover div{background:linear-gradient(transparent,#071a2eee 40%);padding:70mm 18mm 22mm}h1{font-size:44pt;margin:0;line-height:1.05}.sub{font-size:15pt;color:#9fe7d8;margin-top:8pt}
    .p{padding:18mm;page-break-after:always}h2{color:#0b4f6c;font-size:21pt;margin:0 0 10pt}.stats{display:grid;grid-template-columns:repeat(3,1fr);gap:10pt;margin:14pt 0}
    .stats b{display:block;font-size:24pt;color:#0f766e}.stats div{background:#e8f6f3;padding:12pt;border-radius:8pt}table{width:100%;border-collapse:collapse}th{background:#0b4f6c;color:#fff;text-align:left;padding:7pt}td{padding:7pt;border-bottom:1px solid #d4e2e4}img.w{width:100%;border-radius:8pt;margin:10pt 0}</style>"""
    (html / "Annual Report 2025.html").write_text(f"""<!doctype html><meta charset=utf-8>{style}
    <section class=cover style="background-image:url(file:///{(IMAGES / 'photo-sea.jpg').as_posix()})"><div><h1>Annual Report 2025</h1><p class=sub>Kestrelwind Energy: powering what comes next</p></div></section>
    <section class=p><h2>Letter from the Chief Executive</h2><p>2025 was the year our fleet passed two gigawatts. We generated enough clean electricity for 1.9 million homes, and we did it with our best ever safety record.</p>
    <div class=stats><div><b>2.4 GW</b>operating capacity</div><div><b>6.2 TWh</b>generated</div><div><b>0.41</b>lost-time injury rate</div></div>
    <p>Offshore construction started at Northern Reach, our largest project so far. Our community benefit funds paid EUR 11 million to schools, sports clubs and energy-saving schemes near our sites.</p>
    <img class=w src="file:///{(IMAGES / 'photo-solar.jpg').as_posix()}"><p>We remain committed to reaching 5 GW by 2030.</p><p><i>Elena Marin, Chief Executive</i></p></section>
    <section class=p><h2>Financial summary</h2><table><tr><th>EUR million</th><th>2025</th><th>2024</th></tr><tr><td>Revenue</td><td>612</td><td>548</td></tr><tr><td>EBITDA</td><td>344</td><td>296</td></tr><tr><td>Capital expenditure</td><td>488</td><td>351</td></tr><tr><td>Net debt</td><td>1,420</td><td>1,105</td></tr></table>
    <h2 style="margin-top:22pt">Sustainability</h2><p>Avoided emissions reached 4.1 million tonnes of CO2. 94 percent of decommissioned blades were recycled or reused, and biodiversity plans are now in place at all 31 sites.</p></section>""", encoding="utf8")
    (html / "Employee Handbook 2026.html").write_text(f"""<!doctype html><meta charset=utf-8>{style}
    <section class=cover style="background-image:url(file:///{(IMAGES / 'photo-ridge.jpg').as_posix()})"><div><h1>Employee Handbook</h1><p class=sub>Everything you need in your first year</p></div></section>
    <section class=p><h2>Welcome</h2><p>This handbook explains how we look after you, and what we ask in return. Start with the new starter guide on the intranet.</p>
    <h2>Time off</h2><p>You have 28 days of annual leave plus public holidays, and two volunteering days to spend on community projects near our sites.</p>
    <h2>Parental leave</h2><p>All parents receive 26 weeks of full pay, whatever their gender and however their child arrives.</p>
    <h2>Learning</h2><p>Every colleague has EUR 1,500 a year for training, and technicians complete their GWO refresher every two years, on paid time.</p></section>""", encoding="utf8")
    (html / "scan-Blade Inspection Certificate T-117.html").write_text("""<!doctype html><meta charset=utf-8><body style="margin:0;background:#fff"><div id=sheet style="width:1240px;height:1754px;box-sizing:border-box;padding:110px;font:30px 'Times New Roman',serif;color:#222;background:#f7f4ec;transform:rotate(-.5deg)">
    <div style="text-align:center;font-size:46px;font-weight:bold;letter-spacing:2px">NORDSEE BLADE SERVICES</div><div style="text-align:center;margin-bottom:70px">Independent rotor inspection, Hamburg</div>
    <div style="font-size:40px;font-weight:bold;text-align:center;margin-bottom:60px">CERTIFICATE OF INSPECTION</div>
    <p>Certificate number: NBS-2026-4471</p><p>Asset: Turbine T-117, Highmoor Ridge Wind Farm</p><p>Owner: Kestrelwind Energy</p><p>Inspection date: 14 September 2026</p>
    <p style="margin-top:50px">We certify that all three rotor blades were inspected by rope access. Leading edge erosion was found at category 2 on blade B and repaired on site with approved filler.</p>
    <p>No structural cracks, delamination or lightning damage were found. The lightning protection system tested within limits.</p><p>Next inspection due: September 2028.</p>
    <p style="margin-top:130px">Signed: H. Vogel, Lead Blade Inspector</p>
    <div style="margin:60px 0 0 620px;width:320px;height:150px;border:7px solid #a3312b;color:#a3312b;font:bold 46px Arial;display:flex;align-items:center;justify-content:center;transform:rotate(-9deg)">APPROVED</div></div>""", encoding="utf8")


if __name__ == "__main__":
    main()
