<script setup lang="ts">
import { useTilt } from '@/composables/useDesignMotion'

/** 蓝图板：节点、连线、尺寸标注与十字准星；鼠标移动时轻微 3D 倾斜。 */
const { target } = useTilt(6)
// 字符串 ref 只在模板里使用，显式读取一次避免类型检查报未使用。
void target

const nodes = [
  { x: 32, y: 46, w: 112, h: 48, title: '触发输入', sub: 'TRIGGER', hot: false },
  { x: 204, y: 24, w: 116, h: 48, title: '模型推理', sub: 'LLM / 01', hot: true },
  { x: 204, y: 112, w: 116, h: 48, title: '知识检索', sub: 'RETRIEVAL', hot: false },
  { x: 204, y: 200, w: 116, h: 48, title: '条件分支', sub: 'BRANCH', hot: false },
  { x: 376, y: 112, w: 112, h: 48, title: '审批出口', sub: 'HUMAN', hot: true },
]
</script>

<template>
  <div
    ref="target"
    class="af-blueprint af-tilt-surface"
    aria-hidden="true"
  >
    <div class="af-blueprint__bar">
      <span>FIG. 01 — WORKFLOW GRAPH</span>
      <span class="af-blueprint__right">SCALE 1:1 / DSL v2</span>
    </div>
    <div class="af-blueprint__canvas">
      <svg viewBox="0 0 520 280" role="presentation">
        <g class="af-bp-edges">
          <path d="M144 70 C176 70 176 48 204 48" />
          <path d="M144 70 C176 70 176 136 204 136" />
          <path d="M144 70 C176 70 176 224 204 224" />
          <path d="M320 48 C350 48 350 136 376 136" />
          <path d="M320 136 H376" />
          <path d="M320 224 C350 224 350 136 376 136" />
        </g>
        <g class="af-bp-flow">
          <path d="M144 70 C176 70 176 48 204 48" />
          <path d="M320 136 H376" />
        </g>
        <g class="af-bp-dims">
          <path d="M32 262 H488" />
          <path d="M32 256 V268M488 256 V268" />
          <text x="260" y="258" text-anchor="middle">456 UNITS · DETERMINISTIC ROUTE</text>
        </g>
        <g
          v-for="(node, index) in nodes"
          :key="node.title"
        >
          <rect
            class="af-bp-node"
            :class="{ 'af-bp-node--hot': node.hot }"
            :x="node.x"
            :y="node.y"
            :width="node.w"
            :height="node.h"
            rx="3"
          />
          <text
            class="af-bp-text"
            :x="node.x + 12"
            :y="node.y + 21"
          >{{ node.title }}</text>
          <text
            class="af-bp-sub"
            :x="node.x + 12"
            :y="node.y + 37"
          >{{ node.sub }}</text>
          <text
            class="af-bp-index"
            :x="node.x + node.w - 10"
            :y="node.y + 14"
            text-anchor="end"
          >0{{ index + 1 }}</text>
        </g>
        <g class="af-bp-cross">
          <path d="M20 20h14M27 13v14M500 20h-14M493 13v14M20 260h14M27 253v14" />
        </g>
      </svg>
    </div>
    <div class="af-blueprint__foot">
      <span>AF-SHEET / 001</span>
      <span>NODE COUNT · 05</span>
    </div>
  </div>
</template>
