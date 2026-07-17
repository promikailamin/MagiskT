#!/usr/bin/env python3
"""
Generate a simplified Magisk Architecture SVG diagram.
This is a fallback for when graphviz's 'dot' is not available.
Usage: python3 generate_svg.py
Output: work_flow_arch.svg
"""
import xml.etree.ElementTree as ET
import textwrap

def svg(tag, **attrs):
    el = ET.SubElement if isinstance(tag, ET.Element) else ET.Element
    parent = attrs.pop('_parent', None) if attrs.get('_parent') else None
    if parent is not None:
        el = ET.SubElement if isinstance(parent, ET.Element) else ET.Element
        elem = ET.Element(tag, {k.replace('_', '-'): str(v) for k, v in attrs.items()})
        parent.append(elem)
        return elem
    else:
        return ET.Element(tag, {k.replace('_', '-'): str(v) for k, v in attrs.items()})

class SVGWriter:
    def __init__(self):
        self.doc = ET.Element('svg', {
            'xmlns': 'http://www.w3.org/2000/svg',
            'viewBox': '0 0 1200 900',
            'width': '1200',
            'height': '900'
        })
        self.doc.append(ET.Comment(' Magisk Architecture Diagram v1.0 '))
        # Styles
        style = ET.SubElement(self.doc, 'style')
        style.text = """
            text { font-family: Arial, sans-serif; font-size: 11px; }
            .title { font-size: 18px; font-weight: bold; fill: #1a1a1a; }
            .subtitle { font-size: 12px; fill: #555; }
            .box { fill: #fff; stroke: #333; stroke-width: 1.5; }
            .box-blue { fill: #E3F2FD; stroke: #1565C0; stroke-width: 1.5; }
            .box-green { fill: #E8F5E9; stroke: #2E7D32; stroke-width: 1.5; }
            .box-purple { fill: #F3E5F5; stroke: #7B1FA2; stroke-width: 1.5; }
            .box-orange { fill: #FFF3E0; stroke: #E65100; stroke-width: 1.5; }
            .box-red { fill: #FFEBEE; stroke: #C62828; stroke-width: 1.5; }
            .box-teal { fill: #E0F2F1; stroke: #00695C; stroke-width: 1.5; }
            .box-yellow { fill: #FFF8E1; stroke: #F57F17; stroke-width: 1.5; }
            .label { font-size: 10px; fill: #333; text-anchor: middle; }
            .label-bold { font-weight: bold; font-size: 11px; }
            .edge { stroke: #666; stroke-width: 1.5; fill: none; }
            .edge-dashed { stroke: #666; stroke-width: 1.5; fill: none; stroke-dasharray: 5,4; }
            .edge-label { font-size: 9px; fill: #555; text-anchor: middle; }
        """

    def rect(self, x, y, w, h, cls='box', label='', sublabel='', rx=4):
        r = ET.SubElement(self.doc, 'rect', {
            'x': str(x), 'y': str(y), 'width': str(w), 'height': str(h),
            'rx': str(rx), 'class': cls
        })
        if label:
            t = ET.SubElement(self.doc, 'text', {
                'x': str(x + w/2), 'y': str(y + h/2 - 6 if sublabel else y + h/2 + 4),
                'class': 'label label-bold'
            })
            t.text = label
        if sublabel:
            t2 = ET.SubElement(self.doc, 'text', {
                'x': str(x + w/2), 'y': str(y + h/2 + 14),
                'class': 'label'
            })
            t2.text = sublabel
        return r

    def arrow(self, x1, y1, x2, y2, label='', dashed=False, color='#666'):
        cls = 'edge-dashed' if dashed else 'edge'
        style_attr = f'stroke:{color};'
        ET.SubElement(self.doc, 'line', {
            'x1': str(x1), 'y1': str(y1), 'x2': str(x2), 'y2': str(y2),
            'class': cls, 'style': style_attr
        })
        if label:
            mx, my = (x1 + x2) / 2, (y1 + y2) / 2
            t = ET.SubElement(self.doc, 'text', {
                'x': str(mx), 'y': str(my - 5), 'class': 'edge-label',
                'fill': color
            })
            for i, line in enumerate(label.split('\n')):
                tl = t if i == 0 else ET.SubElement(self.doc, 'text', {
                    'x': str(mx), 'y': str(my + 12 * i),
                    'class': 'edge-label', 'fill': color
                })
                if i == 0:
                    t.text = line
                else:
                    tl.text = line

    def save(self, path):
        ET.ElementTree(self.doc).write(path, encoding='utf-8', xml_declaration=True)
        print(f"SVG saved to {path}")

def main():
    s = SVGWriter()
    # Title
    ET.SubElement(s.doc, 'text', {'x': '600', 'y': '30', 'class': 'title', 'text-anchor': 'middle'}).text = 'Magisk Architecture — Main Workflow'
    ET.SubElement(s.doc, 'text', {'x': '600', 'y': '48', 'class': 'subtitle', 'text-anchor': 'middle'}).text = 'boot → daemon → modules → su → zygisk → denylist (with file:method references)'

    # === BOOT (left side, x=50..350) ===
    bx, bw = 50, 280

    # Column header
    s.rect(bx, 65, bw, 30, 'box-purple', 'BOOT PROCESS', '', 2)

    y = 110
    s.rect(bx+10, y, bw-20, 50, 'box-purple', 'magiskinit (PID 1)', 'init/init.rs :: main()', 4)
    s.arrow(bx+bw/2, y, bx+bw/2, y+50, '', False, '#7B1FA2')

    # First Stage
    y += 60
    s.rect(bx+10, y, bw-20, 65, 'box-purple', 'first_stage() [2SI]', 'twostage.rs :: hijack_init_with_switch_root()', 4)
    y += 75
    s.rect(bx+10, y, bw-20, 65, 'box-purple', 'second_stage()', 'rootdir.cpp :: patch_ro_root()\n+ inject init.rc services', 4)

    y2 = y
    y += 75
    s.rect(bx+10, y, bw-20, 50, 'box-purple', 'exec_init()', '→ /system/bin/init', 4)

    s.arrow(bx+bw/2, 160, bx+bw/2, 170, '', False, '#7B1FA2')
    s.arrow(bx+bw/2, 235, bx+bw/2, 245, '', False, '#7B1FA2')
    s.arrow(bx+bw/2, 310, bx+bw/2, 320, '', False, '#7B1FA2')

    # init.rc services
    y = 360
    s.rect(bx+10, y, bw-20, 55, 'box-purple', 'init.rc injected services:', 'magisk --post-fs-data\nmagisk --service\nmagisk --boot-complete', 4)

    # === DAEMON (center-left, x=380..600) ===
    dx, dw = 380, 240

    # Arrow from boot to post-fs-data
    s.arrow(bx+bw, 390, dx, 390, 'init.rc\ntriggers', False, '#1565C0')

    s.rect(dx, 65, dw, 30, 'box-blue', 'BOOT STAGES', '', 2)

    # post-fs-data
    s.rect(dx+10, 110, dw-20, 70, 'box-blue', 'magisk --post-fs-data', 'bootstages.rs :: post_fs_data()\ninit denylist → handle_modules()', 4)

    # late_start
    s.rect(dx+10, 195, dw-20, 60, 'box-blue', 'magisk --service', 'bootstages.rs :: late_start()\nexec service scripts', 4)

    # boot_complete
    s.rect(dx+10, 270, dw-20, 60, 'box-blue', 'magisk --boot-complete', 'bootstages.rs :: boot_complete()\nreset counter, ensure_manager()', 4)

    # daemon
    s.rect(dx+10, 350, dw-20, 70, 'box-blue', 'magiskd (daemon)', 'daemon.rs :: daemon_entry()\nsocket bind, handle_requests()', 4)

    s.arrow(dx+dw/2, 370, dx+dw/2, 350, 'fork', False, '#1565C0')

    # === MODULES (right of daemon) ===
    mx, mw = 650, 250
    s.arrow(dx+dw, 140, mx, 140, 'handle_modules()', True, '#F57F17')

    s.rect(mx, 65, mw, 30, 'box-yellow', 'MODULE SYSTEM', '', 2)

    s.rect(mx+10, 110, mw-20, 65, 'box-yellow', 'module.rs :: collect_modules()', 'Scan /data/adb/modules/*/\nCheck module.prop, flags', 4)
    s.rect(mx+10, 190, mw-20, 65, 'box-yellow', 'module.rs :: apply_modules()', 'FsNode virtual tree → inject\nbins/zygisk → commit()', 4)
    s.rect(mx+10, 270, mw-20, 60, 'box-yellow', 'bind mount overlays', 'module files appear at system/\nSystem partition untouched', 4)

    s.arrow(mx+mw/2, 145, mx+mw/2, 190, '', False, '#F57F17')
    s.arrow(mx+mw/2, 255, mx+mw/2, 270, '', False, '#F57F17')

    # === SU (below daemon) ===
    sy, sw = 470, 240
    s.rect(dx, sy, dw, 30, 'box-green', 'SUPERUSER SYSTEM', '', 2)

    s.arrow(dx+dw/2, 420, dx+dw/2, 475, 'code: SUPERUSER', False, '#2E7D32')

    s.rect(dx+10, 515, dw-20, 55, 'box-green', 'su.cpp :: su_client_main()', 'parse args → connect daemon\n→ PTY pump → exit code', 4)
    s.rect(dx+10, 585, dw-20, 65, 'box-green', 'su/daemon.rs :: su_daemon_handler()', 'check DB policy → fork child\n→ exec_root_shell() (C++)', 4)
    s.rect(dx+10, 665, dw-20, 65, 'box-green', 'su/connect.rs :: connect_app()', 'FIFO IPC with Manager app\nUse am start + content provider', 4)

    s.arrow(dx+dw/2, 545, dx+dw/2, 585, '', False, '#2E7D32')
    s.arrow(dx+dw/2, 650, dx+dw/2, 665, '', False, '#2E7D32')

    # === ZYGISK (right, x=930..1170) ===
    zx, zw = 930, 240
    s.rect(zx, 65, zw, 30, 'box-red', 'ZYGISK (ZYGOTE INJECTION)', '', 2)

    s.arrow(bx+bw, 95, zx, 80, 'setprop ro.dalvik.vm.\nnative.bridge=libzygisk.so', True, '#C62828')

    s.rect(zx+10, 110, zw-20, 65, 'box-red', 'entry.cpp :: NativeBridgeItf', 'isCompatibleWith() → hook_entry()\nreturn false → dlclose fires', 4)
    s.rect(zx+10, 190, zw-20, 70, 'box-red', 'hook.cpp :: hook_plt()', 'PLT hooks: dlclose, strdup, fork,\nunshare, selinux_android_setcontext', 4)
    s.rect(zx+10, 275, zw-20, 65, 'box-red', 'hook.cpp :: hook_zygote_jni()', 'JNI hooks: nativeForkAndSpecialize\nnativeSpecializeAppProcess', 4)
    s.rect(zx+10, 355, zw-20, 70, 'box-red', 'hook.cpp :: ZygiskContext', 'DenyList check → module load\n→ revert_unmount() if matched', 4)

    s.arrow(zx+zw/2, 145, zx+zw/2, 190, '', False, '#C62828')
    s.arrow(zx+zw/2, 260, zx+zw/2, 275, '', False, '#C62828')
    s.arrow(zx+zw/2, 340, zx+zw/2, 355, '', False, '#C62828')

    # === DENYLIST (below Zygisk) ===
    dy2 = 470
    s.arrow(zx+zw/2, 425, zx+zw/2, dy2+10, 'if on DenyList', False, '#C62828')

    s.rect(zx, dy2, zw, 30, 'box-red', 'DENYLIST', '', 2)
    s.rect(zx+10, dy2+45, zw-20, 55, 'box-red', 'deny/utils.cpp :: revert_unmount()', 'ummount Magisk tmpfs\nummount module mounts', 4)
    s.rect(zx+10, dy2+115, zw-20, 50, 'box-red', 'deny/logcat.cpp :: patch logcat', 'Filter Magisk entries\nfrom denylisted processes', 4)

    s.arrow(zx+zw/2, dy2+45, zx+zw/2, dy2+30, '', False, '#C62828')
    s.arrow(zx+zw/2, dy2+100, zx+zw/2, dy2+115, '', False, '#C62828')

    # === APP (bottom center) ===
    ay, aw, ah = 790, 800, 70
    ax = (1200 - aw) / 2
    s.arrow(dx+dw/2, 730, ax+aw/2, ay+5, 'am start\nintent', False, '#F57F17')

    s.rect(ax, ay, aw, ah, 'box-orange', 'MAGISK MANAGER APP (Android)', 'App.kt → Info.kt → Config.kt → Fragments (Home/Install/Module/Superuser/DenyList/Log/Settings)', 4)

    # Legend
    leg_x, leg_y = 50, 830
    s.rect(leg_x, leg_y, 130, 20, 'box-blue', 'Boot/Daemon', '', 2)
    s.rect(leg_x+140, leg_y, 130, 20, 'box-green', 'SU System', '', 2)
    s.rect(leg_x+280, leg_y, 130, 20, 'box-red', 'Zygisk/DenyList', '', 2)
    s.rect(leg_x+420, leg_y, 130, 20, 'box-yellow', 'Module System', '', 2)
    s.rect(leg_x+560, leg_y, 130, 20, 'box-orange', 'Android App', '', 2)
    s.rect(leg_x+700, leg_y, 130, 20, 'box-purple', 'Init Process', '', 2)

    s.save('/home/mikailamin/Projects/magisk/docs/work_flow_arch.svg')

if __name__ == '__main__':
    main()
