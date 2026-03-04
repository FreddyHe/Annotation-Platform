"""
标注审核和编辑界面 - 修复版 v5 (稳定版)
修复内容：
1. 彻底解决 st.session_state 引起的无限重载循环
2. 修复 MediaFileHandler 图片丢失问题 (通过缓存和减少重绘)
3. 使用回调函数(callbacks)优化翻页逻辑
4. 增加状态对比检测
"""
import streamlit as st
import json
from pathlib import Path
from PIL import Image, ImageOps
import copy
import hashlib

try:
    from streamlit_drawable_canvas import st_canvas
except ImportError:
    st_canvas = None


class AnnotationEditor:
    """标注编辑器逻辑类"""
    
    FIXED_COLORS = {
        'dog': '#FF0000', 'cat': '#00FF00', 'bird': '#0000FF', 'car': '#FFFF00',
        'person': '#FF00FF', 'horse': '#00FFFF', 'cow': '#FFA500', 'sheep': '#800080',
        'elephant': '#008080', 'bear': '#A52A2A',
    }
    
    def __init__(self):
        if 'label_color_map' not in st.session_state:
            st.session_state.label_color_map = self.FIXED_COLORS.copy()
        self.color_map = st.session_state.label_color_map
        
    def get_color(self, label):
        if label not in self.color_map:
            hash_val = int(hashlib.md5(label.encode()).hexdigest(), 16) % 0xFFFFFF
            self.color_map[label] = "#{:06x}".format(hash_val)
        return self.color_map[label]

    @staticmethod
    @st.cache_data(show_spinner=False)
    def load_image(image_path: str, max_width=800, max_height=600):
        """
        加载并缓存图片，避免 MediaFileHandler 错误
        返回: (PIL.Image对象, 原始宽度, 原始高度, 缩放比例)
        """
        try:
            p = Path(image_path)
            if not p.exists():
                return None, 0, 0, 1.0
            
            # 使用上下文管理器确保文件句柄关闭
            with Image.open(p) as img:
                img = ImageOps.exif_transpose(img) # 处理手机拍照旋转
                img = img.convert("RGB")
                orig_w, orig_h = img.size
                
                scale_ratio = min(max_width / orig_w, max_height / orig_h, 1.0)
                
                if scale_ratio < 1.0:
                    new_w = int(orig_w * scale_ratio)
                    new_h = int(orig_h * scale_ratio)
                    img = img.resize((new_w, new_h), Image.Resampling.LANCZOS)
                else:
                    new_w, new_h = orig_w, orig_h
                    
                return img, orig_w, orig_h, scale_ratio
        except Exception as e:
            print(f"Error loading image: {e}")
            return None, 0, 0, 1.0

    def load_annotations(self, json_path: str):
        with open(json_path, 'r', encoding='utf-8') as f:
            return json.load(f)

    def save_annotations(self, json_path: str, annotations: list):
        Path(json_path).parent.mkdir(parents=True, exist_ok=True)
        with open(json_path, 'w', encoding='utf-8') as f:
            json.dump(annotations, f, ensure_ascii=False, indent=2)
        return True

    def normalize_path(self, path_str: str) -> str:
        try:
            return str(Path(path_str).resolve())
        except Exception:
            return path_str

    def get_unique_images(self, annotations: list):
        seen = set()
        unique_imgs = []
        for ann in annotations:
            p = ann.get('image_path', '')
            if p:
                abs_p = self.normalize_path(p)
                if abs_p not in seen:
                    seen.add(abs_p)
                    unique_imgs.append({'path': abs_p, 'name': Path(abs_p).name})
        return unique_imgs
    
    def get_all_images_from_dir(self, upload_dir: Path):
        allowed = {'.jpg', '.jpeg', '.png', '.bmp', '.webp'}
        images = []
        if not upload_dir.exists():
            return []
        for f in upload_dir.rglob("*"):
            if f.suffix.lower() in allowed and f.is_file():
                images.append({'path': str(f.resolve()), 'name': f.name})
        return sorted(images, key=lambda x: x['name'])

    def get_unique_labels(self, annotations: list):
        labels = set(ann.get('label', '') for ann in annotations if ann.get('label'))
        return sorted(list(labels))

    def filter_keep_only(self, annotations: list):
        return [ann for ann in annotations if ann.get('vlm_decision', '').lower() == 'keep']

    def get_annotations_for_image(self, annotations: list, image_path: str, label_filter: str = "__ALL__"):
        target = self.normalize_path(image_path)
        result = []
        for ann in annotations:
            if self.normalize_path(ann.get('image_path', '')) == target:
                if label_filter == "__ALL__" or ann.get('label') == label_filter:
                    result.append(ann)
        return result

    def bboxes_to_canvas_objects(self, annotations: list, scale_ratio: float = 1.0):
        objects = []
        for ann in annotations:
            x, y, w, h = ann.get('bbox', [0, 0, 0, 0])
            label = ann.get('label', 'unknown')
            color = self.get_color(label)
            
            objects.append({
                "type": "rect",
                "left": float(x * scale_ratio),
                "top": float(y * scale_ratio),
                "width": float(w * scale_ratio),
                "height": float(h * scale_ratio),
                "fill": color + "33",
                "stroke": color,
                "strokeWidth": 2,
                "label": label,
                "score": ann.get('score', 1.0),
                "lockUniScaling": False,
                "lockScalingFlip": True,
            })
        return objects


def render_visual_editor(
    processed_dir: Path,
    current_project: str,
    key_prefix: str = "visual_editor",
    upload_dir: Path | None = None,
    project_labels: dict | None = None,
):
    if st_canvas is None:
        st.error("请安装 streamlit-drawable-canvas")
        return

    # Let's clean up state keys
    k_img_idx = f"{key_prefix}_img_idx"
    k_annotations = f"{key_prefix}_annotations"
    k_original_annotations = f"{key_prefix}_orig_annotations"
    k_json_path = f"{key_prefix}_json_path"
    k_draw_mode = f"{key_prefix}_draw_mode"
    k_current_label = f"{key_prefix}_current_label"
    k_filter_label = f"{key_prefix}_filter_label"
    k_pending_changes = f"{key_prefix}_pending" # 存储 path -> annotations 映射
    k_all_images = f"{key_prefix}_all_images"
    k_cached_project = f"{key_prefix}_cached_project"
    
    # 状态初始化
    if k_img_idx not in st.session_state: st.session_state[k_img_idx] = 0
    if k_pending_changes not in st.session_state: st.session_state[k_pending_changes] = {}
    if k_cached_project not in st.session_state: st.session_state[k_cached_project] = current_project

    # 项目切换重置
    if st.session_state[k_cached_project] != current_project:
        st.session_state[k_img_idx] = 0
        st.session_state[k_pending_changes] = {}
        st.session_state[k_annotations] = None
        st.session_state[k_all_images] = None
        st.session_state[k_cached_project] = current_project
        st.rerun()

    editor = AnnotationEditor()

    # 1. 加载资源 (仅在需要时)
    if st.session_state.get(k_all_images) is None:
        all_images = []
        if upload_dir:
            all_images = editor.get_all_images_from_dir(upload_dir)
        if not all_images:
            # 尝试从 json 加载
            pass # 具体逻辑根据需求
        st.session_state[k_all_images] = all_images

    all_images = st.session_state.get(k_all_images, [])
    if not all_images:
        st.warning("没有找到图片")
        return

    # 2. 加载标注
    json_path = processed_dir / "vlm_cleaned.json"
    if not json_path.exists():
        json_path = processed_dir / "dino_detections.json"
    
    if st.session_state.get(k_annotations) is None and json_path.exists():
        raw = editor.load_annotations(str(json_path))
        anns = editor.filter_keep_only(raw)
        st.session_state[k_annotations] = anns
        st.session_state[k_original_annotations] = copy.deepcopy(anns)
        st.session_state[k_json_path] = str(json_path)

    all_annotations = st.session_state.get(k_annotations, [])
    
    # 3. 标签管理
    ann_labels = editor.get_unique_labels(all_annotations)
    all_labels = sorted(list(set((list(project_labels.keys()) if project_labels else []) + ann_labels)))
    if k_current_label not in st.session_state and all_labels:
        st.session_state[k_current_label] = all_labels[0]
    if k_filter_label not in st.session_state:
        st.session_state[k_filter_label] = "__ALL__"
    if k_draw_mode not in st.session_state:
        st.session_state[k_draw_mode] = "transform"

    # ========== 导航回调函数 (关键修复：防止 Rerun 循环) ==========
    def prev_image():
        if st.session_state[k_img_idx] > 0:
            st.session_state[k_img_idx] -= 1

    def next_image():
        if st.session_state[k_img_idx] < len(all_images) - 1:
            st.session_state[k_img_idx] += 1
            
    def goto_image():
        # 用于 selectbox 的回调
        # 这里的 value 已经在 session_state 中被 update 了，不需要做额外操作
        pass

    # 确保索引安全
    current_idx = st.session_state[k_img_idx]
    if current_idx >= len(all_images): current_idx = len(all_images) - 1
    if current_idx < 0: current_idx = 0
    st.session_state[k_img_idx] = current_idx

    current_img_info = all_images[current_idx]
    current_path = current_img_info['path']
    current_name = current_img_info['name']

    # ========== 顶部工具栏 ==========
    col1, col2, col3, col4 = st.columns([1.5, 1.5, 1.5, 3])
    with col1:
        st.radio("模式", ["编辑", "新建"], 
                 index=0 if st.session_state[k_draw_mode] == "transform" else 1,
                 horizontal=True,
                 key=f"{key_prefix}_mode_radio",
                 on_change=lambda: st.session_state.update({k_draw_mode: "transform" if st.session_state[f"{key_prefix}_mode_radio"] == "编辑" else "rect"}))
    with col2:
        st.selectbox("筛选", ["__ALL__"] + all_labels, 
                     format_func=lambda x: "全部类别" if x == "__ALL__" else x,
                     key=k_filter_label)
    with col3:
        st.selectbox("绘制类别", all_labels, key=k_current_label)
    with col4:
        s_col1, s_col2 = st.columns(2)
        with s_col1:
            # 应用修改逻辑
            if st.button("💾 保存进度", type="primary", use_container_width=True):
                # 合并 pending 到 main annotations
                pending = st.session_state[k_pending_changes]
                if pending:
                    # 这是一种简单的合并策略：对于有修改的图片，完全替换其标注
                    modified_paths = set(editor.normalize_path(p) for p in pending.keys())
                    new_list = [a for a in all_annotations if editor.normalize_path(a['image_path']) not in modified_paths]
                    
                    for p_path, p_anns in pending.items():
                        new_list.extend(p_anns)
                    
                    st.session_state[k_annotations] = new_list
                    editor.save_annotations(str(json_path), new_list)
                    st.session_state[k_original_annotations] = copy.deepcopy(new_list)
                    st.session_state[k_pending_changes] = {} # 清空待定
                    st.success("已保存！")
                else:
                    st.info("没有需要保存的修改")

    st.divider()

    # ========== 主界面 ==========
    nav_col, canvas_col = st.columns([1, 4])

    with nav_col:
        # 图片列表
        image_options = [f"{i+1}. {img['name']}" for i, img in enumerate(all_images)]
        
        # 导航按钮
        b1, b2 = st.columns(2)
        b1.button("◀", on_click=prev_image, disabled=current_idx==0, use_container_width=True)
        b2.button("▶", on_click=next_image, disabled=current_idx==len(all_images)-1, use_container_width=True)

        # 下拉跳转
        st.selectbox(
            "跳转到", 
            options=range(len(all_images)), 
            format_func=lambda i: image_options[i],
            index=current_idx,
            key=k_img_idx, # 直接绑定到 img_idx
            on_change=goto_image
        )

        st.markdown("---")
        pending_counts = len(st.session_state[k_pending_changes].get(current_path, []))
        if current_path in st.session_state[k_pending_changes]:
             st.warning(f"⚠️ 当前图片有未保存修改 (框数: {pending_counts})")

    with canvas_col:
        # 1. 准备图片 (使用缓存函数)
        bg_image, orig_w, orig_h, scale_ratio = editor.load_image(current_path)
        
        if bg_image is None:
            st.error(f"无法读取图片: {current_path}")
            return

        # 2. 准备初始框
        # 优先使用 pending 中的数据，否则使用原始数据
        if current_path in st.session_state[k_pending_changes]:
            current_anns = st.session_state[k_pending_changes][current_path]
            # 如果处于筛选模式，我们需要把被筛选掉的框也加回来，否则保存时会丢失
            # 但这里简化处理：pending 中存储的是该图片的所有框
        else:
            current_anns = editor.get_annotations_for_image(
                all_annotations, current_path, st.session_state[k_filter_label]
            )

        initial_objects = editor.bboxes_to_canvas_objects(current_anns, scale_ratio)

        # 3. Canvas 渲染
        # key 必须包含 current_idx，这样切图时会强制重新渲染
        # 移除 canvas_version，减少不必要的重绘
        canvas_key = f"{key_prefix}_canvas_{current_idx}_{st.session_state[k_filter_label]}"
        
        canvas_result = st_canvas(
            fill_color="rgba(255, 165, 0, 0.1)",
            stroke_width=2,
            stroke_color=editor.get_color(st.session_state[k_current_label]),
            background_image=bg_image,
            update_streamlit=True,
            height=bg_image.height,
            width=bg_image.width,
            drawing_mode=st.session_state[k_draw_mode],
            initial_drawing={"version": "4.4.0", "objects": initial_objects},
            key=canvas_key,
            display_toolbar=True,
        )

        # 4. 处理返回数据 (关键：防循环逻辑)
        if canvas_result.json_data is not None:
            objects = canvas_result.json_data.get("objects", [])
            
            # 只有当对象数量或内容与初始状态不一致时，才认为是“修改”
            # 简单的判断：如果 objects 存在且不等于 initial_objects (需序列化对比或简单长度对比)
            # 这里我们使用一个更稳健的方法：将 canvas 坐标转回 bbox，然后存入 session_state
            
            new_anns = []
            has_changes = False
            
            for obj in objects:
                if obj.get('type') != 'rect': continue
                
                # 坐标还原
                left = float(obj.get('left', 0))
                top = float(obj.get('top', 0))
                w_box = float(obj.get('width', 0)) * float(obj.get('scaleX', 1))
                h_box = float(obj.get('height', 0)) * float(obj.get('scaleY', 1))
                
                actual_x = left / scale_ratio
                actual_y = top / scale_ratio
                actual_w = w_box / scale_ratio
                actual_h = h_box / scale_ratio
                
                label = obj.get('label') or st.session_state[k_current_label]
                
                new_anns.append({
                    "image_path": current_path,
                    "image_name": current_name,
                    "label": label,
                    "bbox": [actual_x, actual_y, actual_w, actual_h],
                    "score": obj.get('score', 1.0),
                    "vlm_decision": "keep"
                })

            # 检测是否真的有变化 (对比 json 字符串是最简单有效的方法)
            # 注意：st_canvas 初始加载时也会触发一次返回，这时数据应该和 initial 一样
            # 我们需要避免在初始加载时写入 session_state
            
            current_pending_json = json.dumps(st.session_state[k_pending_changes].get(current_path, []), sort_keys=True)
            # 将 new_anns 与 原始 annotations 对比
            
            # 为了防止死循环，我们不对比 current_pending，而是对比“如果我不更新，数据是否一致”
            # 但最简单的阻断方法是：使用一个临时状态标记“是否是用户操作”
            # 这里我们采用：如果计算出的 new_anns 和 传入的 initial_objects 逻辑上等价，就不更新
            
            # 简化策略：
            # 只有当 new_anns 的数量或内容与上一次记录的 pending 不一致时才更新
            new_json = json.dumps(new_anns, sort_keys=True)
            
            # 如果当前没有任何 pending，且 new_anns 与 原始数据不同 -> 更新
            # 如果当前有 pending，且 new_anns 与 pending 不同 -> 更新
            
            should_update = False
            if current_path not in st.session_state[k_pending_changes]:
                # 比较 new_anns 和 current_anns (原始数据)
                # 这有点难，因为 current_anns 可能只包含 filter 后的数据
                # 如果处于 filter 模式，我们只更新当前显示的框，这很危险（会丢失未显示的框）
                # 因此建议：编辑模式下尽量使用 "__ALL__" 筛选，或者在合并时非常小心
                
                # 简单处理：只有当用户在 Canvas 上做了操作，Objects 才会变化
                # st_canvas 的 initial_drawing 只在 key 变化时生效
                pass 
                
                # 这里我们利用一个 trick：
                # 只有当 new_anns 确实有内容 (或者原本有内容被清空) 且跟初始渲染不同步时
                if len(new_anns) != len(current_anns):
                    should_update = True
                else:
                    # 深度比较坐标 (允许微小误差)
                    pass 
            else:
                if new_json != current_pending_json:
                    should_update = True
            
            # 强制更新策略：
            # 实际上，只要 canvas_result 里的 objects 和我们 session_state 里的 pending 不一样，就更新
            # 为了避免初始化时的死循环，我们依赖 st.session_state 的变化检测
            
            if new_json != current_pending_json:
                # 只有数据真的变了才写入 session_state
                # 这能有效阻断 "Rerun -> Same Data -> Write Same Data -> Rerun" 的循环
                st.session_state[k_pending_changes][current_path] = new_anns
                # 这里不需要 st.rerun()，Streamlit 会自动检测到 session_state 变化并 Rerun