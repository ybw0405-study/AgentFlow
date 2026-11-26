<script setup lang="ts">
import { useTilt } from '@/composables/useDesignMotion'

/** 等轴测「仪器」：纯 CSS 3D 层叠，鼠标移动时跟随倾斜。 */
const { target } = useTilt(10)
// 模板里通过字符串 ref 绑定，显式读一次值避免类型检查报未使用。
void target.value
</script>

<template>
  <div
    ref="target"
    class="af-iso af-tilt-surface"
    aria-hidden="true"
  >
    <div class="af-iso__stage">
      <div class="af-iso__plate af-iso__plate--base"><span>ENGINE</span></div>
      <div class="af-iso__plate af-iso__plate--mid"><span>ROUTER</span></div>
      <div class="af-iso__plate af-iso__plate--top"><span>DSL</span></div>
      <div class="af-iso__orbit af-iso__orbit--outer" />
      <div class="af-iso__orbit af-iso__orbit--inner" />
      <div class="af-iso__core" />
    </div>
    <div class="af-iso__caption">
      <span>ISO VIEW / 旋转中</span>
      <span class="af-iso__value">RUNTIME · 02</span>
    </div>
  </div>
</template>
