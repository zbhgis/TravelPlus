/**
 * 【游记发布页的实现】#/edit 页面渲染模块
 * 职责：标题/正文输入、图片多选上传（最多9张）、图片删除、游记发布/编辑、关联景点（可选）
 * 依赖：api.js (post/get/put/del)、ui.js (imgUrl/imgError/toast)、store.js (isLoggedIn)
 * 核心链路：
 *   renderNoteEdit(container)
 *     → 未登录 → 重定向 #/login?redirect=edit
 *     → 解析 hash query ?id= → 编辑模式 or 新建模式
 *     → 编辑模式：GET /note/{id} 预填（标题/正文/图片/可见性/关联景点）→ 标题"编辑游记"→ 保存调 PUT /note/{id}
 *     → 新建模式：空白表单 → 标题"发布游记"→ 保存调 POST /note
 *     → 图片上传：选文件 → FormData POST /upload/note → 返回相对路径名 → 加入 selectedFiles[] → 渲染预览
 *     → 图片删除：× 按钮 → GET /upload/note/delete?name= → 从 selectedFiles 移除 → 重渲染预览
 *     → 发布/保存：校验标题/正文非空 → body {title, content, images(逗号分隔), attractionId?, visible} → toast → 跳 #/note/{id}
 *
 * 【游记可见性的实现】发布表单可见性开关（公开/仅自己可见）
 * - toggle 开关：checked=公开(visible=0)，unchecked=仅自己可见(visible=1)
 * - 编辑模式预填 note.visible
 * - 提交 body 附带 visible 字段
 *
 * 【游记编辑的实现】编辑模式支持
 * - 通过 hash query ?id=xxx 识别编辑模式
 * - GET /note/{id} 预填表单（标题/正文/图片/可见性/关联景点）
 * - 保存调用 PUT /note/{id}（仅作者可编辑，后端校验）
 * - 新建模式下保存调用 POST /note
 *
 * 【游记关联景点的实现】发布表单关联景点（可选）
 * - 搜索输入框 + 下拉选择：输入景点名关键字 → GET /attraction/of/name?name= → 下拉列表
 * - 选中景点后显示景点卡片（名+图），可取消选择
 * - 提交 body 附加 attractionId（有选中时）
 */
import { post, get, put, del } from '../api.js';
import { imgUrl, imgError, toast } from '../ui.js';
import { isLoggedIn } from '../store.js';

let selectedFiles = [];
let selectedAttraction = null;  // 【游记关联景点的实现】选中的关联景点 {id, name, images}

export async function renderNoteEdit(container) {
  if (!isLoggedIn()) {
    window.location.hash = '#/login?redirect=edit';
    return;
  }

  /** 【游记编辑的实现】解析 hash query ?id= 判断编辑/新建模式
   * 实现细节：
   * - 从 window.location.hash 中提取 query string（如 #/edit?id=5 → id=5）
   * - 有 id → 编辑模式（GET 预填 + PUT 保存）
   * - 无 id → 新建模式（POST 保存）
   */
  const hash = window.location.hash.slice(1); // e.g. "/edit?id=5"
  const qIndex = hash.indexOf('?');
  const queryStr = qIndex !== -1 ? hash.slice(qIndex + 1) : '';
  const searchParams = new URLSearchParams(queryStr);
  const editId = searchParams.get('id');
  const isEditMode = !!editId;

  // 编辑模式：预填数据
  let prefilledNote = null;
  if (isEditMode) {
    try {
      prefilledNote = await get('/note/' + editId);
      // 预填图片列表（逗号分隔 → 数组）
      if (prefilledNote.images) {
        selectedFiles = prefilledNote.images.split(',').filter(Boolean);
      }
      // 预填关联景点
      if (prefilledNote.attractionId && prefilledNote.attraction) {
        const attrImg = prefilledNote.attraction.images ? prefilledNote.attraction.images.split(',')[0] : '';
        selectedAttraction = {
          id: prefilledNote.attractionId,
          name: prefilledNote.attraction.name || '',
          images: attrImg
        };
      }
    } catch {
      toast('获取游记失败');
      window.history.back();
      return;
    }
  } else {
    selectedFiles = [];
    selectedAttraction = null;
  }

  const titleLabel = isEditMode ? '编辑游记' : '发布游记';
  const submitLabel = isEditMode ? '保存' : '发布';
  // 【游记可见性的实现】默认 visible=0 公开（新建），编辑模式读取原值
  const noteVisible = prefilledNote ? (prefilledNote.visible === 0 || prefilledNote.visible === false ? 0 : 1) : 0;
  const visibleChecked = noteVisible === 0;

  container.innerHTML = `
    <div class="page-header">
      <button class="header-back" id="edit-back">
        <svg width="24" height="24" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><path d="m15 18-6-6 6-6"/></svg>
      </button>
      <h2>${titleLabel}</h2>
    </div>
    <div class="edit-form">
      <div class="form-group">
        <label>标题</label>
        <input type="text" id="edit-title" placeholder="给游记起个标题" value="${prefilledNote ? (prefilledNote.title || '') : ''}"/>
      </div>
      <div class="form-group">
        <label>正文</label>
        <textarea id="edit-content" placeholder="分享你的旅行故事..." rows="6">${prefilledNote ? (prefilledNote.content || '') : ''}</textarea>
      </div>
      ${/** 【游记关联景点的实现】关联景点搜索/选择 */
      '<div class="form-group">' +
        '<label>关联景点（可选）</label>' +
        '<div id="linked-attraction-area">' +
          (selectedAttraction ? '' : '<input type="text" id="attraction-search" placeholder="搜索景点名称..." autocomplete="off"/><div class="attraction-search-dropdown" id="attraction-dropdown" style="display:none"></div>') +
        '</div>' +
      '</div>'}
      ${/** 【游记可见性的实现】可见性开关 toggle
         * 实现细节：
         * - checked=公开(visible=0)，unchecked=仅自己可见(visible=1)
         * - 与隐私开关样式一致（.toggle-switch + .toggle-slider）
         * - 编辑模式回显原 visible 值
         * - 提交 body 附带 visible 字段（Integer 0/1）
         */
      '<div class="form-group">' +
        '<label>可见性</label>' +
        '<div class="privacy-toggles">' +
          '<div class="privacy-toggle-item">' +
            '<span id="visibility-label">' + (visibleChecked ? '公开' : '仅自己可见') + '</span>' +
            '<label class="toggle-switch">' +
              '<input type="checkbox" id="toggle-note-visible"' + (visibleChecked ? ' checked' : '') + '/>' +
              '<span class="toggle-slider"></span>' +
            '</label>' +
          '</div>' +
        '</div>' +
      '</div>'}
      <div class="form-group">
        <label>图片（最多9张）</label>
        <div class="image-upload-list" id="image-list">
          <label class="image-upload-add" id="image-add-btn">
            <svg width="28" height="28" viewBox="0 0 24 24" fill="none" stroke="#ccc" stroke-width="1.5"><circle cx="12" cy="12" r="10"/><path d="M12 8v8"/><path d="M8 12h8"/></svg>
          </label>
        </div>
        <input type="file" id="image-input" accept="image/*" multiple hidden/>
      </div>
      <button class="btn btn-primary btn-block mt-16" id="edit-submit">${submitLabel}</button>
    </div>
  `;

  const titleInput = container.querySelector('#edit-title');
  const contentInput = container.querySelector('#edit-content');
  const imageList = container.querySelector('#image-list');
  const addBtn = container.querySelector('#image-add-btn');
  const fileInput = container.querySelector('#image-input');

  container.querySelector('#edit-back').addEventListener('click', () => window.history.back());

  addBtn.addEventListener('click', () => fileInput.click());

  // 【游记可见性的实现】可见性开关切换 → 更新 label 文本
  const visibilityToggle = container.querySelector('#toggle-note-visible');
  const visibilityLabel = container.querySelector('#visibility-label');
  if (visibilityToggle) {
    visibilityToggle.addEventListener('change', () => {
      visibilityLabel.textContent = visibilityToggle.checked ? '公开' : '仅自己可见';
    });
  }

  fileInput.addEventListener('change', async () => {
    const files = Array.from(fileInput.files);
    if (selectedFiles.length + files.length > 9) {
      toast('最多上传9张图片');
      fileInput.value = '';
      return;
    }

    for (const file of files) {
      if (selectedFiles.length >= 9) break;
      try {
        const formData = new FormData();
        formData.append('file', file);
        const name = await post('/upload/note', formData);
        selectedFiles.push(name);
        renderImageList();
      } catch (e) {
        toast(e.message || '上传失败');
      }
    }
    fileInput.value = '';
  });

  function renderImageList() {
    const existing = imageList.querySelectorAll('.image-upload-item');
    existing.forEach(el => el.remove());

    selectedFiles.forEach((name, index) => {
      const item = document.createElement('div');
      item.className = 'image-upload-item';
      const url = imgUrl(name);
      item.innerHTML = `
        <img src="${url}" alt="" onerror="imgError(event)"/>
        <button class="img-remove" data-index="${index}">×</button>
      `;
      item.querySelector('.img-remove').addEventListener('click', async () => {
        try { await get('/upload/note/delete?name=' + encodeURIComponent(name)); } catch {}
        selectedFiles.splice(index, 1);
        renderImageList();
      });
      imageList.insertBefore(item, addBtn);
    });
  }

  // 初始渲染图片列表（包括编辑模式预填的图片）
  if (selectedFiles.length > 0) {
    renderImageList();
  }

  /** 【游记关联景点的实现】景点搜索输入防抖 + 下拉选择
   *  - 输入 ≥ 1 字符后延迟 300ms 搜索
   *  - GET /attraction/of/name?name= → 下拉列表（最多显示 5 条）
   *  - 点击某一项 → 设置为 selectedAttraction → 渲染已选卡片
   *  - 可点击 × 取消选择
   */
  const searchInput = container.querySelector('#attraction-search');
  const dropdown = container.querySelector('#attraction-dropdown');
  let searchTimer = null;

  if (searchInput) {
    searchInput.addEventListener('input', () => {
      clearTimeout(searchTimer);
      const q = searchInput.value.trim();
      if (!q) {
        dropdown.style.display = 'none';
        return;
      }
      searchTimer = setTimeout(async () => {
        try {
          const data = await get('/attraction/of/name?name=' + encodeURIComponent(q) + '&current=1');
          const list = Array.isArray(data) ? data : (data && data.records ? data.records : []);
          const top5 = list.slice(0, 5);
          if (!top5.length) {
            dropdown.innerHTML = '<div class="dropdown-empty">未找到匹配景点</div>';
            dropdown.style.display = 'block';
            return;
          }
          dropdown.innerHTML = top5.map(a => {
            const img = a.images ? a.images.split(',')[0] : '';
            return '<div class="dropdown-item" data-id="' + a.id + '" data-name="' + (a.name || '') + '" data-img="' + (img || '') + '">' +
              (img ? '<img src="' + imgUrl(img) + '" alt="" onerror="imgError(event)"/>' : '<span class="dropdown-no-img">无图</span>') +
              '<span>' + (a.name || '') + '</span>' +
              '</div>';
          }).join('');
          dropdown.style.display = 'block';

          // 绑定下拉项点击
          dropdown.querySelectorAll('.dropdown-item').forEach(item => {
            item.addEventListener('click', () => {
              selectedAttraction = {
                id: item.dataset.id,
                name: item.dataset.name,
                images: item.dataset.img
              };
              renderLinkedAttraction(container);
              dropdown.style.display = 'none';
              if (searchInput) searchInput.value = '';
            });
          });
        } catch {
          dropdown.style.display = 'none';
        }
      }, 300);
    });
  }

  // 点击外部关闭下拉
  if (searchInput) {
    document.addEventListener('click', (e) => {
      if (!searchInput.contains(e.target) && !dropdown.contains(e.target)) {
        dropdown.style.display = 'none';
      }
    });
  }

  /** 【游记关联景点的实现】渲染已选关联景点卡片
   *  - 显示景点缩略图 + 名称 + × 取消按钮
   *  - 替换搜索输入框位置
   */
  function renderLinkedAttraction(containerEl) {
    const area = containerEl.querySelector('#linked-attraction-area');
    if (!selectedAttraction) {
      area.innerHTML = `
        <input type="text" id="attraction-search" placeholder="搜索景点名称..." autocomplete="off"/>
        <div class="attraction-search-dropdown" id="attraction-dropdown" style="display:none"></div>
      `;
      // 重新绑定搜索事件
      const newSearchInput = area.querySelector('#attraction-search');
      const newDropdown = area.querySelector('#attraction-dropdown');
      if (newSearchInput) {
        newSearchInput.addEventListener('input', () => {
          clearTimeout(searchTimer);
          const q = newSearchInput.value.trim();
          if (!q) { newDropdown.style.display = 'none'; return; }
          searchTimer = setTimeout(async () => {
            try {
              const data = await get('/attraction/of/name?name=' + encodeURIComponent(q) + '&current=1');
              const list = Array.isArray(data) ? data : (data && data.records ? data.records : []);
              const top5 = list.slice(0, 5);
              if (!top5.length) {
                newDropdown.innerHTML = '<div class="dropdown-empty">未找到匹配景点</div>';
                newDropdown.style.display = 'block';
                return;
              }
              newDropdown.innerHTML = top5.map(a => {
                const img = a.images ? a.images.split(',')[0] : '';
                return '<div class="dropdown-item" data-id="' + a.id + '" data-name="' + (a.name || '') + '" data-img="' + (img || '') + '">' +
                  (img ? '<img src="' + imgUrl(img) + '" alt="" onerror="imgError(event)"/>' : '<span class="dropdown-no-img">无图</span>') +
                  '<span>' + (a.name || '') + '</span>' +
                  '</div>';
              }).join('');
              newDropdown.style.display = 'block';
              newDropdown.querySelectorAll('.dropdown-item').forEach(item => {
                item.addEventListener('click', () => {
                  selectedAttraction = { id: item.dataset.id, name: item.dataset.name, images: item.dataset.img };
                  renderLinkedAttraction(containerEl);
                  newDropdown.style.display = 'none';
                });
              });
            } catch { newDropdown.style.display = 'none'; }
          }, 300);
        });
        document.addEventListener('click', function closeDropdown(e) {
          if (!newSearchInput.contains(e.target) && !newDropdown.contains(e.target)) {
            newDropdown.style.display = 'none';
          }
        });
      }
      return;
    }

    const img = selectedAttraction.images;
    area.innerHTML = `
      <div class="linked-attraction-card">
        ${img ? '<img class="linked-attraction-img" src="' + imgUrl(img) + '" alt="" onerror="imgError(event)"/>' : ''}
        <div class="linked-attraction-info">
          <div class="linked-attraction-name">${selectedAttraction.name}</div>
          <div class="linked-attraction-hint">已关联景点</div>
        </div>
        <button class="linked-attraction-remove" id="linked-remove">×</button>
      </div>
    `;
    area.querySelector('#linked-remove').addEventListener('click', () => {
      selectedAttraction = null;
      renderLinkedAttraction(containerEl);
    });
  }

  // 编辑模式：若已有关联景点，渲染已选卡片
  if (selectedAttraction) {
    renderLinkedAttraction(container);
  }

  // Submit
  container.querySelector('#edit-submit').addEventListener('click', async () => {
    const title = titleInput.value.trim();
    const content = contentInput.value.trim();
    if (!title) { toast('请输入标题'); return; }
    if (!content) { toast('请输入正文'); return; }

    const btn = container.querySelector('#edit-submit');
    btn.disabled = true;
    btn.textContent = isEditMode ? '保存中...' : '发布中...';

    try {
      const imagesStr = selectedFiles.join(',');
      /** 【游记可见性的实现】visible: 0=公开(checked), 1=仅自己可见(unchecked)
       *  - 与后端 body.visible 字段对应（Integer 0/1）
       *  - 编辑模式 PUT 连同 visible 一起更新
       */
      const visible = visibilityToggle && visibilityToggle.checked ? 0 : 1;
      const body = { title, content, images: imagesStr, visible };
      /** 【游记关联景点的实现】附带 attractionId */
      if (selectedAttraction && selectedAttraction.id) {
        body.attractionId = parseInt(selectedAttraction.id);
      }
      /** 【游记编辑的实现】编辑模式 PUT /note/{id}，新建模式 POST /note */
      const noteId = isEditMode
        ? await put('/note/' + editId, body)
        : await post('/note', body);
      /** 【游记发布的实现】发布/编辑成功后置脏标记
       * 实现细节：
       * - 写 localStorage travelplus_notes_dirty='1'
       * - 首页游记频道/关注频道渲染时检测此标记 → 强制重新拉取第一页
       * - 确保发布后返回首页时新游记即时出现
       */
      localStorage.setItem('travelplus_notes_dirty', '1');
      toast(isEditMode ? '保存成功' : '发布成功');
      window.location.hash = '#/note/' + (isEditMode ? editId : noteId);
    } catch {
      btn.disabled = false;
      btn.textContent = isEditMode ? '保存' : '发布';
    }
  });

  return () => {};
}
