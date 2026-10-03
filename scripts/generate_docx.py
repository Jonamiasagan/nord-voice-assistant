import docx
from docx.shared import Inches, Pt, RGBColor
from docx.enum.text import WD_ALIGN_PARAGRAPH
from docx.enum.table import WD_TABLE_ALIGNMENT
from docx.oxml import parse_xml
from docx.oxml.ns import nsdecls

def create_devil_specification_docx(output_path: str):
    doc = docx.Document()

    # Set standard margins
    for section in doc.sections:
        section.top_margin = Inches(1)
        section.bottom_margin = Inches(1)
        section.left_margin = Inches(1)
        section.right_margin = Inches(1)

    # Helper for shading table cells
    def set_cell_background(cell, fill_hex):
        shading_elm = parse_xml(f'<w:shd {nsdecls("w")} w:fill="{fill_hex}"/>')
        cell._tc.get_or_add_tcPr().append(shading_elm)

    # Title
    title_p = doc.add_paragraph()
    title_p.paragraph_format.space_before = Pt(0)
    title_p.paragraph_format.space_after = Pt(4)
    title_p.alignment = WD_ALIGN_PARAGRAPH.CENTER
    run_title = title_p.add_run('DEVIL AI VOICE ASSISTANT')
    run_title.font.name = 'Arial'
    run_title.font.size = Pt(24)
    run_title.font.bold = True
    run_title.font.color.rgb = RGBColor(0, 114, 206)

    # Subtitle
    sub_p = doc.add_paragraph()
    sub_p.paragraph_format.space_after = Pt(20)
    sub_p.alignment = WD_ALIGN_PARAGRAPH.CENTER
    run_sub = sub_p.add_run('Voice Biometrics, Speaker Recognition & Boss-Guest Access Protocol\nTechnical Architectural Specification')
    run_sub.font.name = 'Arial'
    run_sub.font.size = Pt(13)
    run_sub.font.italic = True
    run_sub.font.color.rgb = RGBColor(90, 105, 120)

    # Section 1: Executive Overview
    h1 = doc.add_heading('1. Executive Overview & Problem Statement', level=1)
    h1.paragraph_format.space_before = Pt(14)
    doc.add_paragraph(
        'Standard commercial voice assistants (Google Assistant, Apple Siri, Amazon Alexa) often suffer from ambient '
        'trigger vulnerability and lack strict hierarchical access control. In a multi-person environment, anyone saying a wake phrase '
        'can execute commands, query private messages, or manipulate device settings.'
    )
    doc.add_paragraph(
        'DEVIL (Nord Voice Assistant) introduces a hierarchical Voice Biometric & Boss Security System built natively in Kotlin '
        'for Android / OnePlus devices. Under this architecture:\n'
        '• The Boss is the sole primary authority: DEVIL is paired directly to the owner\'s vocal cords and acoustic timbre.\n'
        '• Strict Guest Access Protocol: Any other speaker (friend, colleague, stranger) is strictly ignored or rejected by default.\n'
        '• Introduction Delegation: A guest is only authorized to interact with DEVIL when the Boss explicitly introduces them '
        '(e.g., "DEVIL, meet Yashwanth" or "This is Uma sir"). Upon introduction, DEVIL opens a session-bound guest pass.'
    )

    # Section 2: Voice Biometric Architecture
    doc.add_heading('2. Voice Biometrics & Speaker Recognition Pipeline', level=1)
    doc.add_paragraph(
        'Speaker verification differs fundamentally from speech recognition (STT). While STT converts what is said into text, '
        'speaker verification analyzes who is speaking by modeling the physical characteristics of the human vocal tract.'
    )

    # Table: Architecture Comparison
    table = doc.add_table(rows=1, cols=3)
    table.alignment = WD_TABLE_ALIGNMENT.CENTER
    hdr_cells = table.rows[0].cells
    hdr_cells[0].text = 'Pipeline Layer'
    hdr_cells[1].text = 'Component / Neural Model'
    hdr_cells[2].text = 'Function & Output'
    for cell in hdr_cells:
        set_cell_background(cell, '0072CE')
        for p in cell.paragraphs:
            for r in p.runs:
                r.font.bold = True
                r.font.color.rgb = RGBColor(255, 255, 255)

    data = [
        ('Audio Capture', 'Native AudioRecord / Android PCM', 'Streams 16kHz 16-bit mono audio directly from microphone hardware.'),
        ('Acoustic Feature Extraction', 'Mel-Frequency Filterbanks (MFCC / Fbank)', 'Computes 80-dimensional log Mel filterbank energies over 25ms windows.'),
        ('Speaker Embedding', 'ECAPA-TDNN / FastResNet-34 (TFLite/ONNX)', 'Projects variable-length utterance into a fixed 192-dimensional d-vector (Voiceprint).'),
        ('Vector Scoring', 'Cosine Similarity Comparator', 'Calculates angle between active speaker vector and enrolled Boss vector.'),
        ('Decision Engine', 'BossSecurityManager (Kotlin)', 'Evaluates threshold (0.78), checks Guest Whitelist, and grants/denies execution.')
    ]

    for row_data in data:
        row_cells = table.add_row().cells
        for i, text in enumerate(row_data):
            row_cells[i].text = text
            set_cell_background(row_cells[i], 'F4F6F8' if i % 2 == 0 else 'FFFFFF')

    doc.add_paragraph()

    # Mathematical formulation
    doc.add_heading('3. Mathematical Vector Scoring (Cosine Similarity)', level=2)
    doc.add_paragraph(
        'Given the stored Boss voiceprint vector v_boss and the incoming utterance embedding vector v_input, '
        'DEVIL computes normalized cosine similarity:\n\n'
        '    Similarity = (v_boss · v_input) / (||v_boss|| * ||v_input||)\n\n'
        'Decision Matrix:\n'
        '• Similarity >= 0.78: Classified as BOSS. Full master privileges granted unconditionally.\n'
        '• Similarity < 0.78 and Guest Session Active (Matching Whitelist): Classified as INTRODUCED GUEST. Conversational privileges granted.\n'
        '• Similarity < 0.78 and No Guest Session: Classified as UNAUTHORIZED STRANGER. Command discarded or access denied.'
    )

    # Section 4: Boss & Guest Protocol
    doc.add_heading('4. The Boss & Guest Access Protocol', level=1)
    doc.add_paragraph('The access control protocol operates as a state machine governed by the Boss:')

    doc.add_heading('A. Master Boss Authority', level=2)
    doc.add_paragraph(
        '• Only the Boss can enroll, reset, or introduce guests.\n'
        '• When the Boss speaks, DEVIL provides immediate zero-latency responses for greetings, questions, music playback, and message briefings.'
    )

    doc.add_heading('B. Guest Introduction Protocol', level=2)
    doc.add_paragraph(
        'When the Boss introduces a person, DEVIL extracts the person\'s identity and persona, registers them into BossSecurityManager, '
        'and updates the HUD telemetry:\n'
        '• Example: "DEVIL, meet Uma sir" -> DEVIL activates guest session: "Hello Uma sir! It is an absolute honor to meet you. Guest session authorized for you. How are you doing today, sir?"\n'
        '• Example: "We have a hacker, his name is Yashwanth" -> DEVIL activates guest session: "Greetings Yashwanth! Welcome, it\'s awesome to meet a fellow hacker! Guest privileges authorized. How are you doing today?"\n'
        '• Example: "This is [Any Name]" -> DEVIL: "Hi [Name]! How are you? Guest privileges have been activated. It\'s a real pleasure to meet you!"'
    )

    doc.add_heading('C. Immediate Lockdown Protocol', level=2)
    doc.add_paragraph(
        'At any moment, the Boss can issue a lockdown command:\n'
        '• Phrases: "DEVIL, lock down", "Cancel guest", "Revoke guest", "Boss only mode"\n'
        '• Response: DEVIL immediately revokes all guest privileges, clears the active guest token, and announces: '
        '"Lockdown engaged. Guest privileges for [Name] have been revoked. Boss-only mode is active."'
    )

    doc.add_heading('D. Authorization Inquiries', level=2)
    doc.add_paragraph(
        'Asking "Who is authorized?" or "Authorization status" produces real-time clearance reporting:\n'
        '• If Guest Active: "You are the Boss with master voice clearance. Active guest [Name] is currently authorized to speak with DEVIL."\n'
        '• If Boss Only: "Boss-only mode is active. Master voice clearance is required. No outside guests are authorized."'
    )

    # Section 5: Native Android Implementation
    doc.add_heading('5. Native Android Architecture & Implementation', level=1)
    doc.add_paragraph(
        'The implementation is contained entirely in native Kotlin within the android_app module:\n\n'
        '1. com.example.nordassistant.security.BossSecurityManager:\n'
        '   - Singleton session controller managing isStrictBossMode, isGuestSessionActive, activeGuest, and guestAccessExpiryMs.\n'
        '   - Implements computeCosineSimilarity(), saveBossVoiceprint(), and loadBossVoiceprint().\n'
        '   - Persists state securely in private Android SharedPreferences (devil_boss_security).\n\n'
        '2. com.example.nordassistant.MainActivity:\n'
        '   - Integrates BossSecurityManager with the speech recognition and conversational fast-path.\n'
        '   - Handles dynamic name parsing, VIP greetings (Uma sir, Yashwanth hacker, Baddu, Bethol, Amma), and lockdown commands.\n'
        '   - Drives real-time telemetry updates to the visual HUD.\n\n'
        '3. com.example.nordassistant.ui.GideonFaceView:\n'
        '   - Jetpack Compose holographic HUD interface inspired by S.T.A.R. Labs Gideon.\n'
        '   - Top telemetry bar displays live security clearance:\n'
        '     * Cyan glow for DEVIL // BOSS SECURED\n'
        '     * Amber glow for GUEST SESSION ACTIVE: [NAME]'
    )

    # Section 6: Security & Privacy
    doc.add_heading('6. Privacy, Security & Offline Performance', level=1)
    doc.add_paragraph(
        '• 100% On-Device: Voiceprints, embeddings, and guest whitelist states never leave the local OnePlus handset.\n'
        '• Zero Cloud Dependency: Operates completely offline, guaranteeing zero audio interception or privacy leakage.\n'
        '• Sub-15ms Latency: Vector comparison and guest session state checks execute in under 15 milliseconds, '
        'ensuring instantaneous voice responses.'
    )

    doc.save(output_path)
    print(f'Document successfully created: {output_path}')

if __name__ == '__main__':
    create_devil_specification_docx('DEVIL_VOICE_BIOMETRICS_AND_BOSS_SECURITY_SPECIFICATION.docx')
