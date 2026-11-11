/**
 * 【影像地图切换的实现】地图图层切换共享组件（全站地图统一接入）
 * attachLayerToggle(map, mapEl)：在地图容器左上角挂「卫星/标准」胶囊切换按钮
 * - 卫星档 = AMap.TileLayer.Satellite（影像层）+ RoadNet（路网注记层）叠加——
 *   纯卫星影像无地名/路名，叠注记层才可读；
 * - 标准档移除两层回到默认矢量底图；图层惰性创建、来回切换不销毁（再次切换零成本）；
 * - 按钮左上角定位：旅行页地图右上角已被 路线模式/准星 两按钮占用，左上角全站通用无冲突；
 * - 按钮 click stopPropagation，不触发地图拖拽/标记点击；
 * - 重复调用幂等（容器内已有 toggle 直接跳过）；AMap 未加载时静默不挂载。
 * 使用方：trip-detail.js（本日路线图）、travel.js（路线模式地图）、user.js（足迹图）
 */
export function attachLayerToggle(map, mapEl) {
  if (typeof AMap === 'undefined' || !map || !mapEl) return;
  if (mapEl.querySelector('.map-layer-toggle')) return;
  const satLayer = new AMap.TileLayer.Satellite({ zIndex: 1 });
  const roadLayer = new AMap.TileLayer.RoadNet({ zIndex: 2 });
  let satelliteOn = false;
  const btn = document.createElement('button');
  btn.type = 'button';
  btn.className = 'map-layer-toggle';
  btn.textContent = '卫星';
  mapEl.appendChild(btn);
  btn.addEventListener('click', e => {
    e.stopPropagation();
    satelliteOn = !satelliteOn;
    if (satelliteOn) {
      map.add([satLayer, roadLayer]);
      btn.textContent = '标准';
      btn.classList.add('on');
    } else {
      map.remove([satLayer, roadLayer]);
      btn.textContent = '卫星';
      btn.classList.remove('on');
    }
  });
}
