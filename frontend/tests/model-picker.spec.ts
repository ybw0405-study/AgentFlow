import { describe, expect, it } from 'vitest'
import { mount } from '@vue/test-utils'
import ModelPicker from '@/components/canvas/ModelPicker.vue'

function mountPicker() {
  return mount(ModelPicker, { props: { currentModel: '进程默认端点（AF_LLM_*）' } })
}

describe('模型选择器', () => {
  it('列出市面常见模型，并提供进程默认端点与自定义入口', () => {
    const wrapper = mountPicker()
    const text = wrapper.text()
    for (const name of ['混元 Hy4', 'DeepSeek', '智谱 GLM', 'Kimi', '通义千问', 'OpenAI']) {
      expect(text).toContain(name)
    }
    expect(text).toContain('进程默认端点（AF_LLM_*）')
    expect(text).toContain('自定义 OpenAI 兼容端点')
    expect(text).toContain('管理已有连接')
  })

  it('搜索按模型名 / 厂商过滤', async () => {
    const wrapper = mountPicker()
    await wrapper.find('.af-picker__search').setValue('hy4')
    expect(wrapper.text()).toContain('混元 Hy4')
    expect(wrapper.text()).not.toContain('DeepSeek')
    expect(wrapper.text()).not.toContain('智谱 GLM')
  })

  it('搜不到时引导走自定义端点', async () => {
    const wrapper = mountPicker()
    await wrapper.find('.af-picker__search').setValue('完全不存在的服务xyz')
    expect(wrapper.text()).toContain('自定义端点')
  })

  it('点预设把完整预设抛给向导', async () => {
    const wrapper = mountPicker()
    const row = wrapper.findAll('.af-picker__row').find((r) => r.text().includes('混元 Hy4'))
    await row!.trigger('click')
    expect(wrapper.emitted('select')?.[0][0]).toMatchObject({
      id: 'hunyuan-hy4',
      model: 'hy4',
      baseUrl: 'https://api.hunyuan.cloud.tencent.com/v1',
    })
  })

  it('进程默认端点、自定义、管理连接各自抛出对应事件', async () => {
    const wrapper = mountPicker()
    const builtin = wrapper.findAll('.af-picker__row').find((r) => r.text().includes('进程默认端点'))
    await builtin!.trigger('click')
    await wrapper.find('.af-picker__foot .af-btn--primary').trigger('click')
    const manage = wrapper.findAll('.af-picker__foot button').find((b) => b.text().includes('管理已有连接'))
    await manage!.trigger('click')
    expect(wrapper.emitted('builtin')).toHaveLength(1)
    expect(wrapper.emitted('custom')).toHaveLength(1)
    expect(wrapper.emitted('manage')).toHaveLength(1)
  })

  it('键盘 Enter 选中当前项，Esc 关闭', async () => {
    const wrapper = mountPicker()
    await wrapper.find('.af-picker__search').setValue('hy4')
    await wrapper.find('.af-picker__search').trigger('keydown', { key: 'Enter' })
    expect(wrapper.emitted('select')?.[0][0]).toMatchObject({ id: 'hunyuan-hy4' })
    await wrapper.find('.af-picker__search').trigger('keydown', { key: 'Escape' })
    expect(wrapper.emitted('close')).toHaveLength(1)
  })
})
