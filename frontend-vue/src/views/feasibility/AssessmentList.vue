<template>
  <div class="assessment-list">
    <PageHeading eyebrow="SOLUTION ASSESSMENT" title="可行性评估" description="从需求解析、开放词汇检测到数据集检索与资源估算，形成可执行的实施方案。">
      <PlatformButton @click="handleCreate">＋ 新建评估</PlatformButton>
    </PageHeading>
    <PanelCard title="评估列表" description="需求评估与实施方案记录" :padded="false">
      <el-table :data="assessments" v-loading="loading" style="width: 100%" @row-click="handleRowClick">
        <el-table-column prop="assessmentName" label="评估名称" min-width="200"><template #default="{ row }"><span class="link-text">{{ row.assessmentName }}</span></template></el-table-column>
        <el-table-column prop="status" label="状态" width="150"><template #default="{ row }"><StatusPill :tone="getStatusTone(row.status)">{{ getStatusText(row.status) }}</StatusPill></template></el-table-column>
        <el-table-column prop="createdAt" label="创建时间" width="180"><template #default="{ row }">{{ formatDate(row.createdAt) }}</template></el-table-column>
        <el-table-column label="操作" width="180" align="center"><template #default="{ row }"><el-button type="primary" size="small" @click.stop="handleView(row.id)">查看</el-button><el-button type="danger" size="small" @click.stop="handleDelete(row.id)">删除</el-button></template></el-table-column>
      </el-table>
    </PanelCard>
  </div>
</template>
<script setup>
import { ref, onMounted } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { feasibilityAPI } from '@/api/feasibility'
import PageHeading from '@/components/platform-ui/PageHeading.vue'
import PanelCard from '@/components/platform-ui/PanelCard.vue'
import PlatformButton from '@/components/platform-ui/PlatformButton.vue'
import StatusPill from '@/components/platform-ui/StatusPill.vue'
const router = useRouter(); const assessments = ref([]); const loading = ref(false)
const getStatusType = (status) => ({ CREATED: 'info', PARSING: 'primary', PARSED: 'primary', OVD_TESTING: 'primary', OVD_TESTED: 'primary', EVALUATING: 'warning', EVALUATED: 'warning', DATASET_SEARCHED: 'warning', AWAITING_USER_JUDGMENT: 'warning', ESTIMATING: 'warning', COMPLETED: 'success', FAILED: 'danger' }[status] || 'info')
const getStatusTone = (status) => ({ success: 'success', danger: 'danger', warning: 'warning', primary: 'active', info: 'neutral' }[getStatusType(status)] || 'neutral')
const getStatusText = (status) => ({ CREATED: '已创建', PARSING: '解析中', PARSED: '已解析', OVD_TESTING: 'OVD测试中', OVD_TESTED: 'OVD已测试', EVALUATING: '评估中', EVALUATED: '已评估', DATASET_SEARCHED: '已检索数据集', AWAITING_USER_JUDGMENT: '已提交判断', ESTIMATING: '估算中', COMPLETED: '已完成', FAILED: '失败' }[status] || status)
const formatDate = (dateStr) => { if (!dateStr) return '-'; return new Date(dateStr).toLocaleString('zh-CN', { year: 'numeric', month: '2-digit', day: '2-digit', hour: '2-digit', minute: '2-digit' }) }
const loadAssessments = async () => { loading.value = true; try { const res = await feasibilityAPI.listAssessments(); assessments.value = res.data || [] } catch (error) { console.error('加载评估列表失败:', error) } finally { loading.value = false } }
const handleCreate = () => { router.push('/feasibility/create') }
const handleView = (id) => { router.push('/feasibility/' + id) }
const handleRowClick = (row) => { router.push('/feasibility/' + row.id) }
const handleDelete = async (id) => { try { await ElMessageBox.confirm('确定要删除此评估吗？', '提示', { confirmButtonText: '确定', cancelButtonText: '取消', type: 'warning' }); await feasibilityAPI.deleteAssessment(id); ElMessage.success('删除成功'); loadAssessments() } catch (error) { if (error !== 'cancel') console.error('删除失败:', error) } }
onMounted(() => { loadAssessments() })
</script>
<style scoped>
.assessment-list { }
.link-text { color: var(--brand-600); cursor: pointer; }
.link-text:hover { text-decoration: underline; }
:deep(.el-table__row) { cursor: pointer; }
</style>
