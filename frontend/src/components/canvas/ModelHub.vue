<script setup lang="ts">
import { ref } from 'vue'
import ModelPicker from './ModelPicker.vue'
import ModelConnectDialog from './ModelConnectDialog.vue'
import type { ModelPreset } from '@/dsl/model-presets'
import type { ConnectModelResult } from '@/dsl/model-connection'

defineOptions({ name: 'ModelHub' })

defineProps<{
  currentModel: string
}>()

const emit = defineEmits<{
  connected: [result: ConnectModelResult]
  builtin: []
  manage: []
  close: []
}>()

/** 两步：先选模型，再填端点与 Key。选完即弹出配置，不要求用户先去「连接器」页。 */
const step = ref<'pick' | 'connect'>('pick')
const selected = ref<ModelPreset | null>(null)

function openPreset(preset: ModelPreset): void {
  selected.value = preset
  step.value = 'connect'
}

function openCustom(): void {
  selected.value = null
  step.value = 'connect'
}

function back(): void {
  step.value = 'pick'
}
</script>

<template>
  <ModelPicker
    v-if="step === 'pick'"
    :current-model="currentModel"
    @select="openPreset"
    @custom="openCustom"
    @builtin="emit('builtin')"
    @manage="emit('manage')"
    @close="emit('close')"
  />
  <ModelConnectDialog
    v-else
    :preset="selected"
    :current-model="currentModel"
    @connected="emit('connected', $event)"
    @back="back"
    @close="emit('close')"
  />
</template>
