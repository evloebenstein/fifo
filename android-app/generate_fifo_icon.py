import math
import os
from PIL import Image, ImageDraw, ImageFilter

def draw_smooth_arc(draw, center, radius_x, radius_y, start_deg, end_deg, color, stroke_w, steps=150):
    """Draw a silky-smooth anti-aliased curved arc with round caps using joint='curve'"""
    cx, cy = center
    points = []
    for i in range(steps + 1):
        angle = math.radians(start_deg + (end_deg - start_deg) * (i / float(steps)))
        x = cx + radius_x * math.cos(angle)
        y = cy + radius_y * math.sin(angle)
        points.append((x, y))
        
    draw.line(points, fill=color, width=stroke_w, joint="curve")
    
    # Rounded end caps
    r = stroke_w / 2.0
    for pt in [points[0], points[-1]]:
        draw.ellipse([pt[0] - r, pt[1] - r, pt[0] + r, pt[1] + r], fill=color)

def create_fifo_assets():
    S = 2048
    
    # ── 1. BACKGROUND (Deep slate-navy with subtle cyan ambient radial aura) ──
    bg = Image.new("RGBA", (S, S), (15, 23, 42, 255)) # #0F172A
    draw_bg = ImageDraw.Draw(bg)
    
    for y in range(S):
        factor = y / float(S)
        r = int(24 * (1 - factor) + 10 * factor)
        g = int(36 * (1 - factor) + 16 * factor)
        b = int(58 * (1 - factor) + 32 * factor)
        draw_bg.line([(0, y), (S, y)], fill=(r, g, b, 255))
        
    # Ambient radial glow in center
    glow = Image.new("RGBA", (S, S), (0, 0, 0, 0))
    draw_glow = ImageDraw.Draw(glow)
    cx, cy = S // 2, int(S * 0.54)
    max_radius = int(S * 0.44)
    for r in range(max_radius, 0, -10):
        alpha = int(48 * (1.0 - (r / max_radius)**1.4))
        draw_glow.ellipse(
            [cx - r, cy - r, cx + r, cy + r],
            fill=(56, 189, 248, alpha)
        )
    glow = glow.filter(ImageFilter.GaussianBlur(radius=35))
    bg = Image.alpha_composite(bg, glow)

    # ── 2. FOREGROUND LAYER (FIFO ROBOT) ──
    # Scaled to fit within 68% safe zone for Android adaptive icons
    fg = Image.new("RGBA", (S, S), (0, 0, 0, 0))
    
    # Dimensions
    head_w = int(S * 0.55)      # ~1126 px
    head_h = int(S * 0.41)      # ~840 px
    head_l = (S - head_w) // 2
    head_t = int(S * 0.38)
    head_r = head_l + head_w
    head_b = head_t + head_h
    head_radius = int(head_h * 0.38) # rounded corners
    
    # Antenna coordinates
    antenna_stem_w = int(S * 0.026) # ~53 px
    antenna_stem_top = int(S * 0.23)
    antenna_stem_bot = head_t + 12
    antenna_cx = S // 2
    
    beacon_cy = antenna_stem_top
    beacon_r = int(S * 0.046) # ~94 px
    
    # Ear sensors coordinates
    ear_w = int(S * 0.065) # ~133 px
    ear_h = int(head_h * 0.40) # ~336 px
    ear_radius = ear_w // 2
    ear_cy = head_t + head_h // 2
    
    # --- A. ANTENNA BEACON GLOW ---
    beacon_glow = Image.new("RGBA", (S, S), (0, 0, 0, 0))
    draw_bglow = ImageDraw.Draw(beacon_glow)
    for gr in range(int(beacon_r * 2.8), 0, -6):
        ga = int(85 * (1.0 - gr / (beacon_r * 2.8)))
        draw_bglow.ellipse(
            [antenna_cx - gr, beacon_cy - gr, antenna_cx + gr, beacon_cy + gr],
            fill=(56, 189, 248, ga)
        )
    beacon_glow = beacon_glow.filter(ImageFilter.GaussianBlur(radius=20))
    fg = Image.alpha_composite(fg, beacon_glow)
    
    # --- B. ANTENNA STEM ---
    draw_fg = ImageDraw.Draw(fg)
    draw_fg.rounded_rectangle(
        [antenna_cx - antenna_stem_w // 2, antenna_stem_top,
         antenna_cx + antenna_stem_w // 2, antenna_stem_bot],
        radius=antenna_stem_w // 2,
        fill=(148, 163, 184, 255) # Titanium Slate 400
    )
    
    # --- C. BEACON SPHERE ---
    draw_fg.ellipse(
        [antenna_cx - beacon_r, beacon_cy - beacon_r,
         antenna_cx + beacon_r, beacon_cy + beacon_r],
        fill=(56, 189, 248, 255) # Radiant Cyan
    )
    # Beacon soft glossy glint
    glint = Image.new("RGBA", (S, S), (0, 0, 0, 0))
    draw_glint = ImageDraw.Draw(glint)
    draw_glint.ellipse(
        [antenna_cx - int(beacon_r * 0.55), beacon_cy - int(beacon_r * 0.55),
         antenna_cx - int(beacon_r * 0.05), beacon_cy - int(beacon_r * 0.05)],
        fill=(255, 255, 255, 200)
    )
    glint = glint.filter(ImageFilter.GaussianBlur(radius=4))
    fg = Image.alpha_composite(fg, glint)
    draw_fg = ImageDraw.Draw(fg)

    # --- D. EAR SENSORS & SHADOWS ---
    left_ear_l = head_l - int(ear_w * 0.60)
    left_ear_r = left_ear_l + ear_w
    left_ear_t = ear_cy - ear_h // 2
    left_ear_b = ear_cy + ear_h // 2
    
    right_ear_r = head_r + int(ear_w * 0.60)
    right_ear_l = right_ear_r - ear_w
    right_ear_t = left_ear_t
    right_ear_b = left_ear_b

    # Ear drop shadows onto background
    ear_shadow = Image.new("RGBA", (S, S), (0, 0, 0, 0))
    draw_es = ImageDraw.Draw(ear_shadow)
    draw_es.rounded_rectangle([left_ear_l - 6, left_ear_t + 10, left_ear_r + 6, left_ear_b + 18], radius=ear_radius, fill=(0, 0, 0, 95))
    draw_es.rounded_rectangle([right_ear_l - 6, right_ear_t + 10, right_ear_r + 6, right_ear_b + 18], radius=ear_radius, fill=(0, 0, 0, 95))
    ear_shadow = ear_shadow.filter(ImageFilter.GaussianBlur(radius=16))
    fg = Image.alpha_composite(fg, ear_shadow)
    draw_fg = ImageDraw.Draw(fg)

    # Draw Ear capsules
    draw_fg.rounded_rectangle([left_ear_l, left_ear_t, left_ear_r, left_ear_b], radius=ear_radius, fill=(226, 232, 240, 255))
    draw_fg.rounded_rectangle([right_ear_l, right_ear_t, right_ear_r, right_ear_b], radius=ear_radius, fill=(226, 232, 240, 255))
    
    # Ear inner cyan LED indicator
    ind_r = int(ear_w * 0.18)
    draw_fg.ellipse([left_ear_l + int(ear_w * 0.20) - ind_r, ear_cy - ind_r, left_ear_l + int(ear_w * 0.20) + ind_r, ear_cy + ind_r], fill=(56, 189, 248, 255))
    draw_fg.ellipse([right_ear_r - int(ear_w * 0.20) - ind_r, ear_cy - ind_r, right_ear_r - int(ear_w * 0.20) + ind_r, ear_cy + ind_r], fill=(56, 189, 248, 255))

    # --- E. HEAD SHELL SHADOW ---
    shadow_layer = Image.new("RGBA", (S, S), (0, 0, 0, 0))
    draw_shadow = ImageDraw.Draw(shadow_layer)
    draw_shadow.rounded_rectangle([head_l, head_t + 35, head_r, head_b + 55], radius=head_radius, fill=(0, 0, 0, 135))
    shadow_layer = shadow_layer.filter(ImageFilter.GaussianBlur(radius=38))
    fg = Image.alpha_composite(fg, shadow_layer)
    draw_fg = ImageDraw.Draw(fg)

    # --- F. CERAMIC HEAD SHELL ---
    head_mask = Image.new("L", (S, S), 0)
    draw_hmask = ImageDraw.Draw(head_mask)
    draw_hmask.rounded_rectangle([head_l, head_t, head_r, head_b], radius=head_radius, fill=255)
    
    head_grad = Image.new("RGBA", (S, S), (0, 0, 0, 0))
    draw_hgrad = ImageDraw.Draw(head_grad)
    for y in range(head_t, head_b + 1):
        f = (y - head_t) / float(head_h)
        cr = int(255 * (1 - f) + 241 * f)
        cg = int(255 * (1 - f) + 245 * f)
        cb = int(255 * (1 - f) + 249 * f)
        draw_hgrad.line([(head_l, y), (head_r, y)], fill=(cr, cg, cb, 255))
        
    fg.paste(head_grad, (0, 0), head_mask)
    draw_fg = ImageDraw.Draw(fg)
    
    # Outer crisp rim border for ceramic shell
    draw_fg.rounded_rectangle([head_l, head_t, head_r, head_b], radius=head_radius, outline=(218, 226, 239, 255), width=int(S * 0.005))

    # --- G. OLED VISOR SCREEN ---
    v_pad_x = int(head_w * 0.10)
    v_pad_y = int(head_h * 0.13)
    vl = head_l + v_pad_x
    vr = head_r - v_pad_x
    vt = head_t + v_pad_y
    vb = head_b - v_pad_y
    vw = vr - vl
    vh = vb - vt
    v_radius = int(vh * 0.38)
    
    visor_mask = Image.new("L", (S, S), 0)
    draw_vmask = ImageDraw.Draw(visor_mask)
    draw_vmask.rounded_rectangle([vl, vt, vr, vb], radius=v_radius, fill=255)
    
    visor_grad = Image.new("RGBA", (S, S), (0, 0, 0, 0))
    draw_vgrad = ImageDraw.Draw(visor_grad)
    for y in range(vt, vb + 1):
        f = (y - vt) / float(vh)
        vr_val = int(11 * (1 - f) + 15 * f)
        vg_val = int(17 * (1 - f) + 23 * f)
        vb_val = int(32 * (1 - f) + 42 * f)
        draw_vgrad.line([(vl, y), (vr, y)], fill=(vr_val, vg_val, vb_val, 255))
    fg.paste(visor_grad, (0, 0), visor_mask)
    draw_fg = ImageDraw.Draw(fg)
    
    # Visor glossy glass reflection arc at top
    glass_layer = Image.new("RGBA", (S, S), (0, 0, 0, 0))
    draw_glass = ImageDraw.Draw(glass_layer)
    draw_glass.ellipse([vl - int(vw * 0.25), vt - int(vh * 0.65), vr + int(vw * 0.25), vt + int(vh * 0.42)], fill=(255, 255, 255, 16))
    glass_clipped = Image.new("RGBA", (S, S), (0, 0, 0, 0))
    glass_clipped.paste(glass_layer, (0, 0), visor_mask)
    fg = Image.alpha_composite(fg, glass_clipped)
    draw_fg = ImageDraw.Draw(fg)

    # Visor rim
    draw_fg.rounded_rectangle([vl, vt, vr, vb], radius=v_radius, outline=(30, 41, 59, 255), width=int(S * 0.004))

    # --- H. EXPRESSIVE FACE FEATURES ---
    vc_x = (vl + vr) // 2
    vc_y = (vt + vb) // 2
    
    eye_spacing = int(vw * 0.23)
    eye_left_cx = vc_x - eye_spacing
    eye_right_cx = vc_x + eye_spacing
    eye_cy = vc_y - int(vh * 0.05)
    
    eye_rx = int(vw * 0.088)
    eye_ry = int(vh * 0.155)
    
    # 1. Warm Rosy Cheeks (soft coral pink)
    blush_layer = Image.new("RGBA", (S, S), (0, 0, 0, 0))
    draw_blush = ImageDraw.Draw(blush_layer)
    blush_rw = int(vw * 0.085)
    blush_rh = int(vh * 0.065)
    blush_y = eye_cy + int(vh * 0.26)
    
    draw_blush.ellipse(
        [eye_left_cx - blush_rw, blush_y - blush_rh, eye_left_cx + blush_rw, blush_y + blush_rh],
        fill=(251, 113, 133, 150) # Coral pink
    )
    draw_blush.ellipse(
        [eye_right_cx - blush_rw, blush_y - blush_rh, eye_right_cx + blush_rw, blush_y + blush_rh],
        fill=(251, 113, 133, 150)
    )
    blush_layer = blush_layer.filter(ImageFilter.GaussianBlur(radius=25))
    fg = Image.alpha_composite(fg, blush_layer)
    draw_fg = ImageDraw.Draw(fg)

    # 2. Glowing Eyes (Silky smooth curved arcs: 195 deg to 345 deg)
    eye_glow = Image.new("RGBA", (S, S), (0, 0, 0, 0))
    draw_eglow = ImageDraw.Draw(eye_glow)
    
    eye_stroke = int(S * 0.021) # ~43 px
    
    # Multi-pass soft outer cyan glow
    for extra_w in [30, 18, 8]:
        g_alpha = int(45 * (1.0 - extra_w / 35.0))
        draw_smooth_arc(draw_eglow, (eye_left_cx, eye_cy), eye_rx, eye_ry, 195, 345, (56, 189, 248, g_alpha), eye_stroke + extra_w)
        draw_smooth_arc(draw_eglow, (eye_right_cx, eye_cy), eye_rx, eye_ry, 195, 345, (56, 189, 248, g_alpha), eye_stroke + extra_w)
        
    eye_glow = eye_glow.filter(ImageFilter.GaussianBlur(radius=8))
    fg = Image.alpha_composite(fg, eye_glow)
    draw_fg = ImageDraw.Draw(fg)

    # Solid cyan eye line
    draw_smooth_arc(draw_fg, (eye_left_cx, eye_cy), eye_rx, eye_ry, 195, 345, (56, 189, 248, 255), eye_stroke)
    draw_smooth_arc(draw_fg, (eye_right_cx, eye_cy), eye_rx, eye_ry, 195, 345, (56, 189, 248, 255), eye_stroke)
    
    # Highlight inside eye
    inner_stroke = int(eye_stroke * 0.42)
    draw_smooth_arc(draw_fg, (eye_left_cx, eye_cy), eye_rx, eye_ry, 220, 320, (224, 242, 254, 255), inner_stroke)
    draw_smooth_arc(draw_fg, (eye_right_cx, eye_cy), eye_rx, eye_ry, 220, 320, (224, 242, 254, 255), inner_stroke)

    # 3. Sweet Gentle Smile
    mouth_rx = int(vw * 0.095)
    mouth_ry = int(vh * 0.12)
    mouth_y = vc_y + int(vh * 0.20)
    mouth_stroke = int(S * 0.016)
    
    # Mouth glow
    mouth_glow = Image.new("RGBA", (S, S), (0, 0, 0, 0))
    draw_mglow = ImageDraw.Draw(mouth_glow)
    draw_smooth_arc(draw_mglow, (vc_x, mouth_y), mouth_rx, mouth_ry, 20, 160, (56, 189, 248, 90), mouth_stroke + 14)
    mouth_glow = mouth_glow.filter(ImageFilter.GaussianBlur(radius=6))
    fg = Image.alpha_composite(fg, mouth_glow)
    draw_fg = ImageDraw.Draw(fg)
    
    # Mouth stroke
    draw_smooth_arc(draw_fg, (vc_x, mouth_y), mouth_rx, mouth_ry, 20, 160, (56, 189, 248, 255), mouth_stroke)
    
    # Inner light smile highlight
    draw_smooth_arc(draw_fg, (vc_x, mouth_y), mouth_rx, mouth_ry, 50, 130, (224, 242, 254, 220), int(mouth_stroke * 0.45))

    # ── COMPOSITING ──
    full_icon = Image.alpha_composite(bg, fg)
    
    # Rounded Squircle Icon (22% radius)
    squircle_mask = Image.new("L", (S, S), 0)
    draw_sq = ImageDraw.Draw(squircle_mask)
    draw_sq.rounded_rectangle([0, 0, S, S], radius=int(S * 0.22), fill=255)
    
    squircle_icon = Image.new("RGBA", (S, S), (0, 0, 0, 0))
    squircle_icon.paste(full_icon, (0, 0), squircle_mask)
    
    # Circular Icon for round launchers (ic_launcher_round)
    circle_mask = Image.new("L", (S, S), 0)
    draw_circ = ImageDraw.Draw(circle_mask)
    draw_circ.ellipse([0, 0, S, S], fill=255)
    
    circle_icon = Image.new("RGBA", (S, S), (0, 0, 0, 0))
    circle_icon.paste(full_icon, (0, 0), circle_mask)

    return full_icon, squircle_icon, circle_icon, fg, bg

def export_all():
    print("Generating Fifo icon art...")
    full_icon, squircle_icon, circle_icon, fg, bg = create_fifo_assets()
    
    res_dir = r"c:\Users\evloe\OneDrive\Escritorio\fifo\android-app\app\src\main\res"
    
    # Android launcher densities:
    # mdpi: 48x48, hdpi: 72x72, xhdpi: 96x96, xxhdpi: 144x144, xxxhdpi: 192x192
    densities = {
        "mipmap-mdpi": (48, 108),
        "mipmap-hdpi": (72, 162),
        "mipmap-xhdpi": (96, 216),
        "mipmap-xxhdpi": (144, 324),
        "mipmap-xxxhdpi": (192, 432)
    }
    
    for folder, (legacy_size, adaptive_size) in densities.items():
        out_folder = os.path.join(res_dir, folder)
        os.makedirs(out_folder, exist_ok=True)
        
        # Standard legacy square/squircle icon
        sq_sized = squircle_icon.resize((legacy_size, legacy_size), Image.Resampling.LANCZOS)
        sq_sized.save(os.path.join(out_folder, "ic_launcher.png"), "PNG")
        
        # Round icon for circular launchers
        circ_sized = circle_icon.resize((legacy_size, legacy_size), Image.Resampling.LANCZOS)
        circ_sized.save(os.path.join(out_folder, "ic_launcher_round.png"), "PNG")
        
        # Adaptive icon foreground (transparent) and background
        fg_adaptive = fg.resize((adaptive_size, adaptive_size), Image.Resampling.LANCZOS)
        fg_adaptive.save(os.path.join(out_folder, "ic_launcher_foreground.png"), "PNG")
        
        bg_adaptive = bg.resize((adaptive_size, adaptive_size), Image.Resampling.LANCZOS)
        bg_adaptive.save(os.path.join(out_folder, "ic_launcher_background.png"), "PNG")
        
        print(f"Exported {folder} (legacy {legacy_size}px, adaptive {adaptive_size}px)")

    # High-res 512x512 for Google Play / store listing
    play_icon = squircle_icon.resize((512, 512), Image.Resampling.LANCZOS)
    play_icon.save(os.path.join(r"c:\Users\evloe\OneDrive\Escritorio\fifo\android-app", "ic_launcher-playstore.png"), "PNG")

    # In-app drawable logo
    drawable_dir = os.path.join(res_dir, "drawable")
    os.makedirs(drawable_dir, exist_ok=True)
    in_app_logo = squircle_icon.resize((256, 256), Image.Resampling.LANCZOS)
    in_app_logo.save(os.path.join(drawable_dir, "ic_fifo_logo.png"), "PNG")
    
    # Save preview artifact
    preview_path = r"C:\Users\evloe\.gemini\antigravity-ide\brain\9e5fc1d5-592c-4ad3-897a-749c62cd68e0\fifo_logo_v3.png"
    squircle_icon.resize((512, 512), Image.Resampling.LANCZOS).save(preview_path, "PNG")
    print(f"Saved preview artifact: {preview_path}")

if __name__ == "__main__":
    export_all()
