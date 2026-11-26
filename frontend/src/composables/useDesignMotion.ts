import { onBeforeUnmount, onMounted, ref } from 'vue'

/**
 * 图纸倾斜交互：鼠标位置映射到两个 CSS 自定义属性，
 * 真正的变换写在 CSS 里，JS 只负责给角度，不碰颜色。
 */
export function useTilt(strength = 7) {
  const target = ref<HTMLElement | null>(null)
  let frame = 0

  function apply(event: MouseEvent) {
    const el = target.value
    if (!el) return
    const rect = el.getBoundingClientRect()
    if (!rect.width || !rect.height) return
    const px = (event.clientX - rect.left) / rect.width - 0.5
    const py = (event.clientY - rect.top) / rect.height - 0.5
    el.style.setProperty('--tilt-y', `${(px * strength).toFixed(2)}deg`)
    el.style.setProperty('--tilt-x', `${(-py * strength).toFixed(2)}deg`)
    el.style.setProperty('--tilt-glow-x', `${((px + 0.5) * 100).toFixed(1)}%`)
    el.style.setProperty('--tilt-glow-y', `${((py + 0.5) * 100).toFixed(1)}%`)
  }

  function onMove(event: MouseEvent) {
    if (frame) return
    frame = window.requestAnimationFrame(() => {
      frame = 0
      apply(event)
    })
  }

  function onLeave() {
    const el = target.value
    if (!el) return
    el.style.setProperty('--tilt-y', '0deg')
    el.style.setProperty('--tilt-x', '0deg')
  }

  onMounted(() => {
    const el = target.value
    if (!el) return
    el.addEventListener('mousemove', onMove)
    el.addEventListener('mouseleave', onLeave)
  })

  onBeforeUnmount(() => {
    const el = target.value
    if (frame) window.cancelAnimationFrame(frame)
    if (!el) return
    el.removeEventListener('mousemove', onMove)
    el.removeEventListener('mouseleave', onLeave)
  })

  return { target }
}

/** 滚动入场：观察器不存在时直接全部显示，避免 jsdom / 老浏览器把内容藏住。 */
export function observeReveal(root: ParentNode = document): () => void {
  const nodes = Array.from(root.querySelectorAll<HTMLElement>('.af-reveal'))
  if (typeof IntersectionObserver === 'undefined') {
    nodes.forEach((node) => node.classList.add('is-in'))
    return () => undefined
  }
  const observer = new IntersectionObserver(
    (entries) => {
      entries.forEach((entry) => {
        if (!entry.isIntersecting) return
        entry.target.classList.add('is-in')
        observer.unobserve(entry.target)
      })
    },
    { threshold: 0.08, rootMargin: '0px 0px -40px' },
  )
  nodes.forEach((node) => observer.observe(node))
  return () => observer.disconnect()
}
