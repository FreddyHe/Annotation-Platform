<template>
  <div class="assessment-detail" v-loading="loading">
    <div class="header">
      <div class="title-section">
        <h2>{{ assessment.assessmentName }}</h2>
        <el-tag :type="getStatusType(assessment.status)" size="large">
          {{ getStatusText(assessment.status) }}
        </el-tag>
      </div>
      <div class="meta-info">
        <span>创建时间：{{ formatDate(assessment.createdAt) }}</span>
      </div>
    </div>

    <el-steps :active="currentStep" finish-status="success" align-center style="margin: 30px 0">
      <el-step title="需求解析" />
      <el-step title="OVD测试" />
      <el-step title="VLM评估" />
      <el-step title="资源估算" />
      <el-step title="实施规划" />
    </el-steps>

    <!-- Step 1: 需求解析 -->
    <el-card class="step-card" v-if="currentStep >= 0">
      <template #header>
        <div class="card-header">
          <span>步骤1：需求解析</span>
        </div>
      </template>

      <div class="step-content">
        <el-form label-width="100px">
          <el-form-item label="原始需求">
            <el-input
              v-model="assessment.rawRequirement"
              type="textarea"
              :rows="4"
              readonly
            />
          </el-form-item>
        </el-form>

        <el-button
          type="primary"
          :disabled="!canParse"
          :loading="parsing"
          @click="handleParse"
        >
          开始解析
        </el-button>

        <div v-if="categories.length > 0" style="margin-top: 20px">
          <h4>解析结果</h4>
          <el-table :data="categories" border>
            <el-table-column prop="categoryName" label="类别名称" width="120" />
            <el-table-column prop="categoryNameEn" label="英文名称" width="150" />
            <el-table-column prop="categoryType" label="类型" width="100" />
            <el-table-column prop="sceneDescription" label="场景描述" min-width="200" />
            <el-table-column prop="viewAngle" label="视角" width="150" />
            <el-table-column label="操作" width="150" align="center">
              <template #default="{ row }">
                <el-button size="small" @click="handleEditCategory(row)">编辑</el-button>
                <el-button size="small" type="danger" @click="handleDeleteCategory(row.id)">删除</el-button>
              </template>
            </el-table-column>
          </el-table>

          <el-button type="success" style="margin-top: 20px" @click="currentStep = 1">
            确认类别，下一步
          </el-button>
        </div>
      </div>
    </el-card>

    <!-- Step 2: OVD测试 -->
    <el-card class="step-card" v-if="currentStep >= 1">
      <template #header>
        <div class="card-header">
          <span>步骤2：OVD测试</span>
        </div>
      </template>

      <div class="step-content">
        <div v-if="assessment.imageUrls && assessment.imageUrls.length > 0">
          <h4>参考图片（{{ assessment.imageUrls.length }}张）</h4>
          <div class="reference-images">
            <el-image
              v-for="(url, index) in assessment.imageUrls"
              :key="index"
              :src="getImageUrl(url)"
              fit="cover"
              :preview-src-list="assessment.imageUrls.map(u => getImageUrl(u))"
              style="width: 120px; height: 120px; margin: 5px; border-radius: 4px;"
            />
          </div>
        </div>
        <el-alert
          v-else
          title="提示"
          type="warning"
          description="未上传参考图片，请返回重新创建评估并上传图片"
          :closable="false"
          style="margin-bottom: 20px"
        />

        <el-button
          type="primary"
          :disabled="!canOvdTest || !assessment.imageUrls || assessment.imageUrls.length === 0"
          :loading="ovdTesting"
          @click="handleRunOvdTest"
          style="margin-top: 20px"
        >
          运行OVD测试
        </el-button>

        <div v-if="ovdResults.length > 0" style="margin-top: 20px">
          <h4>测试结果</h4>
          <div v-for="(group, categoryName) in groupedOvdResults" :key="categoryName">
            <h5>{{ categoryName }}</h5>
            <div class="ovd-results-grid">
              <el-card v-for="result in group" :key="result.id" class="result-card">
                <div class="result-content">
                  <div class="result-image">
                    <el-image
                      :src="getImageUrl(result.annotatedImagePath)"
                      fit="contain"
                      :preview-src-list="[getImageUrl(result.annotatedImagePath)]"
                    />
                  </div>
                  <div class="result-info">
                    <p><strong>Prompt:</strong> {{ result.promptUsed }}</p>
                    <p><strong>检测数:</strong> {{ result.detectedCount }}</p>
                    <p><strong>置信度:</strong> {{ (result.averageConfidence * 100).toFixed(1) }}%</p>
                  </div>
                </div>
              </el-card>
            </div>
          </div>
        </div>
      </div>
    </el-card>

    <!-- Step 3: VLM评估 -->
    <el-card class="step-card" v-if="currentStep >= 2">
      <template #header>
        <div class="card-header">
          <span>步骤3：VLM评估</span>
        </div>
      </template>

      <div class="step-content">
        <el-button
          type="primary"
          :disabled="!canEvaluate"
          :loading="evaluating"
          @click="handleEvaluate"
        >
          开始评估
        </el-button>

        <div v-if="bucketCategories.bucketA.length > 0 || bucketCategories.bucketB.length > 0 || bucketCategories.bucketC.length > 0" style="margin-top: 20px">
          <h4>评估结果（三桶分类）</h4>
          <div class="buckets-grid">
            <!-- 桶A: OVD可用 -->
            <el-card class="bucket-card bucket-a" v-if="bucketCategories.bucketA.length > 0">
              <template #header>
                <div class="bucket-header">
                  <el-icon color="#67c23a"><CircleCheck /></el-icon>
                  <span>桶A - OVD可用</span>
                </div>
              </template>
              <div v-for="cat in bucketCategories.bucketA" :key="cat.id" class="category-item">
                <h5>{{ cat.categoryName }}</h5>
                <p><strong>置信度:</strong> {{ (cat.confidence * 100).toFixed(0) }}%</p>
                <p><strong>原因:</strong> {{ cat.reasoning }}</p>
              </div>
            </el-card>

            <!-- 桶B: 需定制训练 -->
            <el-card class="bucket-card bucket-b" v-if="bucketCategories.bucketB.length > 0">
              <template #header>
                <div class="bucket-header">
                  <el-icon color="#e6a23c"><Warning /></el-icon>
                  <span>桶B - 需定制训练</span>
                </div>
              </template>
              <div v-for="cat in bucketCategories.bucketB" :key="cat.id" class="category-item">
                <h5>{{ cat.categoryName }}</h5>
                <p><strong>置信度:</strong> {{ (cat.confidence * 100).toFixed(0) }}%</p>
                <p><strong>原因:</strong> {{ cat.reasoning }}</p>
              </div>
            </el-card>

            <!-- 桶C: VLM专用 -->
            <el-card class="bucket-card bucket-c" v-if="bucketCategories.bucketC.length > 0">
              <template #header>
                <div class="bucket-header">
                  <el-icon color="#409eff"><InfoFilled /></el-icon>
                  <span>桶C - VLM专用</span>
                </div>
              </template>
              <div v-for="cat in bucketCategories.bucketC" :key="cat.id" class="category-item">
                <h5>{{ cat.categoryName }}</h5>
                <p><strong>置信度:</strong> {{ (cat.confidence * 100).toFixed(0) }}%</p>
                <p><strong>原因:</strong> {{ cat.reasoning }}</p>
              </div>
            </el-card>
          </div>
        </div>

        <!-- VLM评估结果摘要卡片 -->
        <div v-if="vlmEvaluationDetails.length > 0" style="margin-top: 30px">
          <!-- 场景1: 全桶A - OVD效果良好 -->
          <el-alert
            v-if="isAllBucketA"
            title="OVD检测效果良好"
            type="success"
            :closable="false"
            style="margin-bottom: 20px"
          >
            <template #default>
              <div style="font-size: 14px; line-height: 1.6;">
                <p style="margin: 0 0 10px 0;">
                  <strong>评估结果：</strong>所有类别的VLM评估指标均达标（平均准确率 {{ avgPrecision.toFixed(1) }}%，平均召回率 {{ avgRecall.toFixed(1) }}%）
                </p>
                <p style="margin: 0;">
                  <strong>建议：</strong>可直接部署使用OVD模型，无需定制训练。请直接生成实施报告。
                </p>
              </div>
            </template>
          </el-alert>

          <!-- 场景2: 非全桶A - 需要定制训练 -->
          <el-alert
            v-else
            title="OVD检测效果不足，需要定制训练"
            type="warning"
            :closable="false"
            style="margin-bottom: 20px"
          >
            <template #default>
              <div style="font-size: 14px; line-height: 1.6;">
                <p style="margin: 0 0 10px 0;">
                  <strong>评估结果：</strong>部分类别的VLM评估指标未达标（平均准确率 {{ avgPrecision.toFixed(1) }}%，平均召回率 {{ avgRecall.toFixed(1) }}%）
                </p>
                <p style="margin: 0;">
                  <strong>建议：</strong>需要定制训练以提升检测效果。请先查看公开数据集情况，然后进行资源估算。
                </p>
              </div>
            </template>
          </el-alert>
        </div>

        <!-- VLM评估详细结果 -->
        <div v-if="vlmEvaluationDetails.length > 0" style="margin-top: 30px">
          <h4>VLM评估详细结果</h4>
          <el-collapse accordion>
            <el-collapse-item v-for="(categoryDetail, index) in vlmEvaluationDetails" :key="index" :name="index">
              <template #title>
                <div style="display: flex; align-items: center; gap: 10px;">
                  <strong>{{ categoryDetail.categoryName }}</strong>
                  <el-tag type="info" size="small">{{ categoryDetail.imageName }}</el-tag>
                  <el-tag :type="categoryDetail.precision >= 0.7 ? 'success' : categoryDetail.precision >= 0.4 ? 'warning' : 'danger'" size="small">
                    准确率: {{ (categoryDetail.precision * 100).toFixed(1) }}%
                  </el-tag>
                </div>
              </template>
              
              <div class="vlm-details-grid">
                <el-card v-for="detail in categoryDetail.details" :key="detail.id" class="vlm-detail-card" :class="{ 'correct': detail.isCorrect, 'incorrect': !detail.isCorrect }">
                  <div class="detail-header">
                    <el-tag :type="detail.isCorrect ? 'success' : 'danger'" size="small">
                      {{ detail.isCorrect ? '✓ 正确' : '✗ 误检' }}
                    </el-tag>
                    <span class="bbox-index">检测框 #{{ detail.bboxIdx + 1 }}</span>
                  </div>
                  
                  <div class="detail-content">
                    <div class="cropped-image-container">
                      <img 
                        v-if="detail.croppedImagePath" 
                        :src="`/api/v1/upload/view?path=${detail.croppedImagePath}`" 
                        alt="裁剪图"
                        class="cropped-image"
                        @error="handleImageError"
                      />
                      <div v-else class="no-image">无裁剪图</div>
                    </div>
                    
                    <div class="detail-info">
                      <div class="info-item">
                        <strong>提问：</strong>
                        <p class="question-text">{{ detail.question || '无' }}</p>
                      </div>
                      
                      <div class="info-item">
                        <strong>VLM回答：</strong>
                        <el-tag :type="detail.vlmAnswer === '是' || detail.vlmAnswer?.toLowerCase() === 'yes' ? 'success' : 'info'" size="small">
                          {{ detail.vlmAnswer || '无回答' }}
                        </el-tag>
                      </div>
                      
                      <div v-if="detail.errorReason" class="info-item error-reason">
                        <strong>错误原因：</strong>
                        <span>{{ detail.errorReason }}</span>
                      </div>
                    </div>
                  </div>
                </el-card>
              </div>
            </el-collapse-item>
          </el-collapse>
        </div>
      </div>
    </el-card>

    <!-- Step 3.5: 数据集检索 (仅非全桶A时显示，永久保留) -->
    <el-card class="step-card" v-if="currentStep >= 3 && !isAllBucketA">
      <template #header>
        <div class="card-header">
          <span>步骤3.5：数据集检索</span>
        </div>
      </template>

      <div class="step-content">
        <el-button
          type="primary"
          :disabled="assessment.status !== 'EVALUATED'"
          :loading="searchingDatasets"
          @click="handleSearchDatasets"
        >
          检索公开数据集
        </el-button>

        <!-- 数据集检索结果 -->
        <div v-if="datasets.length > 0" style="margin-top: 20px">
          <h4>数据集检索结果</h4>
          <el-alert
            v-if="datasets.some(d => d.datasetName === '未找到公开数据集')"
            type="info"
            :closable="false"
            style="margin-bottom: 15px"
          >
            <template #default>
              <div style="font-size: 13px;">
                <strong>说明：</strong>由于Roboflow Universe网站的反爬虫限制，无法自动获取数据集列表。请点击下方按钮手动前往Roboflow搜索相关数据集。
              </div>
            </template>
          </el-alert>
          <el-table :data="datasets" border>
            <el-table-column prop="categoryName" label="类别" width="120" />
            <el-table-column prop="source" label="来源" width="100" />
            <el-table-column label="数据集名称" min-width="200">
              <template #default="{ row }">
                <a v-if="row.datasetUrl" :href="row.datasetUrl" target="_blank" rel="noopener noreferrer" class="link-text">
                  {{ row.datasetName }}
                </a>
                <span v-else>{{ row.datasetName || '-' }}</span>
              </template>
            </el-table-column>
            <el-table-column label="样本数" width="100">
              <template #default="{ row }">
                {{ formatSampleCount(row.sampleCount) }}
              </template>
            </el-table-column>
            <el-table-column prop="annotationFormat" label="格式" width="120" />
            <el-table-column prop="license" label="许可证" width="120" />
            <el-table-column label="相关度" width="100">
              <template #default="{ row }">
                {{ (row.relevanceScore * 100).toFixed(0) }}%
              </template>
            </el-table-column>
          </el-table>

          <div class="roboflow-action" v-if="roboflowSearchUrl">
            <el-button type="primary" plain @click="openRoboflowSearch">
              前往 Roboflow 查看更多数据集
            </el-button>
          </div>
        </div>

        <!-- 用户判断区域（永久保留） -->
        <div v-if="datasets.length > 0 || assessment.datasetMatchLevel" class="user-judgment-section">
          <h4>用户判断（数据集匹配度）</h4>
          <el-form label-width="110px">
            <el-form-item label="匹配度选择">
              <el-radio-group v-model="judgmentForm.datasetMatchLevel" :disabled="assessment.status !== 'DATASET_SEARCHED'">
                <el-radio label="ALMOST_MATCH">ALMOST_MATCH（几乎一致）</el-radio>
                <el-radio label="PARTIAL_MATCH">PARTIAL_MATCH（部分相关）</el-radio>
                <el-radio label="NOT_USABLE">NOT_USABLE（不可用）</el-radio>
              </el-radio-group>
            </el-form-item>
            <el-form-item label="备注">
              <el-input
                v-model="judgmentForm.userNotes"
                type="textarea"
                :rows="3"
                placeholder="可选：补充场景一致性、差异点等说明"
                :disabled="assessment.status !== 'DATASET_SEARCHED'"
              />
            </el-form-item>
          </el-form>
          <el-button 
            v-if="assessment.status === 'DATASET_SEARCHED'"
            type="primary" 
            :loading="submittingJudgment" 
            @click="handleSubmitUserJudgment"
          >
            确认用户判断
          </el-button>
          <el-tag v-else type="success" style="margin-top: 10px">已提交判断</el-tag>
        </div>
      </div>
    </el-card>

    <!-- Step 4: 资源估算 -->
    <el-card class="step-card" v-if="shouldShowResourceEstimation">
      <template #header>
        <div class="card-header">
          <span>步骤4：资源估算</span>
        </div>
      </template>

      <div class="step-content">
        <el-button
          type="primary"
          :disabled="!canEstimate"
          :loading="estimating"
          @click="handleEstimate"
        >
          开始估算
        </el-button>

        <div v-if="resourceEstimations.length > 0" style="margin-top: 20px">
          <h4>资源估算结果</h4>
          <el-table :data="resourceEstimations" border>
            <el-table-column prop="categoryName" label="类别" width="120" />
            <el-table-column label="路径" width="100">
              <template #default="{ row }">
                <el-tag v-if="row.feasibilityBucket === 'OVD_AVAILABLE'" type="success">桶A</el-tag>
                <el-tag v-else-if="['CUSTOM_LOW', 'CUSTOM_MEDIUM'].includes(row.feasibilityBucket)" type="warning">桶B</el-tag>
                <el-tag v-else-if="row.feasibilityBucket === 'CUSTOM_HIGH'" type="info">桶C</el-tag>
                <el-tag v-else>-</el-tag>
              </template>
            </el-table-column>
            <el-table-column label="公开数据集" width="110">
              <template #default="{ row }">
                {{ formatSampleCount(row.publicDatasetImages || 0) }}
              </template>
            </el-table-column>
            <el-table-column prop="estimatedImages" label="标注图片数" width="110" />
            <el-table-column prop="trainingApproach" label="训练方式" min-width="180" />
            <el-table-column prop="estimatedManDays" label="人天" width="80" />
            <el-table-column prop="gpuHours" label="GPU时" width="80" />
            <el-table-column prop="estimatedTotalDays" label="总天数" width="90" />
            <el-table-column prop="notes" label="备注" min-width="200" />
          </el-table>
        </div>
      </div>
    </el-card>

    <!-- Step 5: 实施规划 -->
    <el-card class="step-card" v-if="currentStep >= 4">
      <template #header>
        <div class="card-header">
          <span>步骤5：实施规划</span>
        </div>
      </template>

      <div class="step-content">
        <el-space>
          <el-button
            type="primary"
            :disabled="!canGenPlan"
            :loading="generatingPlan"
            @click="handleGeneratePlan"
          >
            生成计划
          </el-button>
          
          <el-button
            type="success"
            :disabled="!canGenPlan"
            :loading="generatingAIReport"
            @click="handleGenerateAIReport"
          >
            生成AI可行性报告
          </el-button>
        </el-space>

        <!-- AI报告显示区域 -->
        <div v-if="aiReport" class="ai-report-section" style="margin-top: 30px">
          <el-divider content-position="left">
            <h3>📊 AI生成的可行性报告</h3>
          </el-divider>
          <el-card shadow="never" class="report-card">
            <div class="markdown-body" v-html="renderedReport"></div>
          </el-card>
        </div>

        <div v-if="implementationPlans.length > 0" style="margin-top: 20px">
          <h4>实施计划</h4>
          <el-timeline>
            <el-timeline-item
              v-for="plan in implementationPlans"
              :key="plan.id"
              :timestamp="`${plan.estimatedDays}天`"
              placement="top"
            >
              <el-card>
                <h4>{{ plan.phaseName }}</h4>
                <p>{{ plan.description }}</p>
                <div v-if="plan.tasks">
                  <strong>任务列表：</strong>
                  <ul>
                    <li v-for="(task, idx) in parseTasksJson(plan.tasks)" :key="idx">
                      {{ task }}
                    </li>
                  </ul>
                </div>
                <p><strong>交付物：</strong>{{ plan.deliverables }}</p>
                <p v-if="plan.dependencies"><strong>依赖：</strong>{{ plan.dependencies }}</p>
              </el-card>
            </el-timeline-item>
          </el-timeline>

          <el-button type="success" size="large" @click="showReportDialog = true" style="margin-top: 20px">
            查看完整报告
          </el-button>
        </div>
      </div>
    </el-card>

    <!-- 编辑类别对话框 -->
    <el-dialog v-model="editCategoryDialog" title="编辑类别" width="600px">
      <el-form :model="editingCategory" label-width="120px">
        <el-form-item label="类别名称">
          <el-input v-model="editingCategory.categoryName" />
        </el-form-item>
        <el-form-item label="英文名称">
          <el-input v-model="editingCategory.categoryNameEn" />
        </el-form-item>
        <el-form-item label="类型">
          <el-input v-model="editingCategory.categoryType" />
        </el-form-item>
        <el-form-item label="场景描述">
          <el-input v-model="editingCategory.sceneDescription" type="textarea" :rows="3" />
        </el-form-item>
        <el-form-item label="视角">
          <el-input v-model="editingCategory.viewAngle" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="editCategoryDialog = false">取消</el-button>
        <el-button type="primary" @click="handleSaveCategory">保存</el-button>
      </template>
    </el-dialog>

    <!-- 完整报告对话框 -->
    <el-dialog v-model="showReportDialog" title="完整报告" width="80%" top="5vh">
      <div v-if="report" class="report-content">
        <el-descriptions title="评估概要" :column="2" border>
          <el-descriptions-item label="评估名称">{{ report.assessmentInfo?.assessmentName }}</el-descriptions-item>
          <el-descriptions-item label="状态">{{ getStatusText(report.assessmentInfo?.status) }}</el-descriptions-item>
          <el-descriptions-item label="创建时间">{{ formatDate(report.assessmentInfo?.createdAt) }}</el-descriptions-item>
          <el-descriptions-item label="完成时间">{{ formatDate(report.assessmentInfo?.completedAt) }}</el-descriptions-item>
        </el-descriptions>

        <h3 style="margin-top: 30px">总结</h3>
        <el-descriptions :column="3" border>
          <el-descriptions-item label="类别总数">{{ report.summary?.totalCategories }}</el-descriptions-item>
          <el-descriptions-item label="桶A">{{ report.summary?.bucketA }}</el-descriptions-item>
          <el-descriptions-item label="桶B">{{ report.summary?.bucketB }}</el-descriptions-item>
          <el-descriptions-item label="桶C">{{ report.summary?.bucketC }}</el-descriptions-item>
          <el-descriptions-item label="总天数">{{ report.summary?.totalEstimatedDays }}天</el-descriptions-item>
          <el-descriptions-item label="总成本">¥{{ report.summary?.totalEstimatedCost || 0 }}</el-descriptions-item>
        </el-descriptions>

        <h3 style="margin-top: 30px">类别详情</h3>
        <el-collapse>
          <el-collapse-item v-for="(cat, idx) in report.categories" :key="idx" :title="cat.categoryName">
            <p><strong>英文名:</strong> {{ cat.categoryNameEn }}</p>
            <p><strong>可行性:</strong> {{ cat.feasibilityBucket }}</p>
            <p><strong>置信度:</strong> {{ (cat.confidence * 100).toFixed(0) }}%</p>
            <p><strong>原因:</strong> {{ cat.reasoning }}</p>
            <div v-if="cat.resourceEstimation">
              <h5>资源估算</h5>
              <p>图片数: {{ cat.resourceEstimation.estimatedImages }}</p>
              <p>人天: {{ cat.resourceEstimation.estimatedManDays }}</p>
              <p>GPU时: {{ cat.resourceEstimation.gpuHours }}</p>
              <p>总天数: {{ cat.resourceEstimation.estimatedTotalDays }}</p>
            </div>
          </el-collapse-item>
        </el-collapse>

        <el-button type="primary" @click="handleExportReport" style="margin-top: 20px">
          导出JSON
        </el-button>
      </div>
    </el-dialog>
  </div>
</template>

<script setup>
import { ref, reactive, computed, onMounted } from 'vue'
import { useRoute } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Plus, CircleCheck, Warning, InfoFilled } from '@element-plus/icons-vue'
import { feasibilityAPI } from '@/api/feasibility'

const route = useRoute()
const assessmentId = computed(() => route.params.id)

const loading = ref(false)
const parsing = ref(false)
const ovdTesting = ref(false)
const evaluating = ref(false)
const searchingDatasets = ref(false)
const estimating = ref(false)
const generatingPlan = ref(false)
const generatingAIReport = ref(false)
const submittingJudgment = ref(false)

const assessment = ref({})
const categories = ref([])
const aiReport = ref(null)
const ovdResults = ref([])
const vlmEvaluationDetails = ref([])
const datasets = ref([])
const resourceEstimations = ref([])
const implementationPlans = ref([])
const report = ref(null)

const testImages = ref([])
const testImageList = ref([])
const editCategoryDialog = ref(false)
const showReportDialog = ref(false)
const editingCategory = ref({})
const judgmentForm = reactive({
  datasetMatchLevel: '',
  userNotes: ''
})

const stepMap = {
  CREATED: 0,
  PARSING: 0,
  PARSED: 1,
  OVD_TESTING: 1,
  OVD_TESTED: 2,
  EVALUATING: 2,
  EVALUATED: 3,
  DATASET_SEARCHED: 3,
  AWAITING_USER_JUDGMENT: 3,
  ESTIMATING: 4,
  COMPLETED: 4,
  FAILED: -1
}

const currentStep = computed(() => {
  return stepMap[assessment.value.status] || 0
})

const canParse = computed(() => assessment.value.status === 'CREATED')
const canOvdTest = computed(() => assessment.value.status === 'PARSED')
const canEvaluate = computed(() => assessment.value.status === 'OVD_TESTED')
const canEstimate = computed(() => assessment.value.status === 'AWAITING_USER_JUDGMENT')
const canGenPlan = computed(() => {
  // 全桶A时，评估完成后即可生成报告
  if (isAllBucketA.value && assessment.value.status === 'EVALUATED') return true
  // 非全桶A时，需要完成资源估算后才能生成报告（包括COMPLETED状态）
  return ['ESTIMATING', 'AWAITING_USER_JUDGMENT', 'COMPLETED'].includes(assessment.value.status)
})

const roboflowSearchUrl = computed(() => {
  const row = datasets.value.find(item => item.searchUrl)
  return row ? row.searchUrl : ''
})

const uploadAction = computed(() => {
  return `${import.meta.env.VITE_API_BASE_URL || ''}/api/v1/upload/image`
})

const uploadHeaders = computed(() => {
  const token = localStorage.getItem('token')
  return {
    Authorization: `Bearer ${token}`
  }
})

const groupedOvdResults = computed(() => {
  const grouped = {}
  ovdResults.value.forEach(result => {
    if (!grouped[result.categoryName]) {
      grouped[result.categoryName] = []
    }
    grouped[result.categoryName].push(result)
  })
  return grouped
})

const bucketCategories = computed(() => {
  return {
    bucketA: categories.value.filter(c => c.feasibilityBucket === 'OVD_AVAILABLE'),
    bucketB: categories.value.filter(c => ['CUSTOM_LOW', 'CUSTOM_MEDIUM'].includes(c.feasibilityBucket)),
    bucketC: categories.value.filter(c => c.feasibilityBucket === 'CUSTOM_HIGH')
  }
})

const isAllBucketA = computed(() => {
  if (categories.value.length === 0) return false
  return categories.value.every(c => c.feasibilityBucket === 'OVD_AVAILABLE')
})

const avgPrecision = computed(() => {
  if (vlmEvaluationDetails.value.length === 0) return 0
  const sum = vlmEvaluationDetails.value.reduce((acc, item) => acc + (item.precision || 0), 0)
  return (sum / vlmEvaluationDetails.value.length) * 100
})

const avgRecall = computed(() => {
  if (vlmEvaluationDetails.value.length === 0) return 0
  const sum = vlmEvaluationDetails.value.reduce((acc, item) => acc + (item.recall || 0), 0)
  return (sum / vlmEvaluationDetails.value.length) * 100
})

const shouldShowResourceEstimation = computed(() => {
  // 全桶A时不显示资源估算
  if (isAllBucketA.value) return false
  // 非全桶A时，在用户判断后显示（包括AWAITING_USER_JUDGMENT、ESTIMATING、COMPLETED状态）
  return currentStep.value >= 3 && ['AWAITING_USER_JUDGMENT', 'ESTIMATING', 'COMPLETED'].includes(assessment.value.status)
})

const getStatusType = (status) => {
  const typeMap = {
    CREATED: 'info',
    PARSING: 'primary',
    PARSED: 'primary',
    OVD_TESTING: 'primary',
    OVD_TESTED: 'primary',
    EVALUATING: 'warning',
    EVALUATED: 'warning',
    DATASET_SEARCHED: 'warning',
    AWAITING_USER_JUDGMENT: 'warning',
    ESTIMATING: 'warning',
    COMPLETED: 'success',
    FAILED: 'danger'
  }
  return typeMap[status] || 'info'
}

const getStatusText = (status) => {
  const textMap = {
    CREATED: '已创建',
    PARSING: '解析中',
    PARSED: '已解析',
    OVD_TESTING: 'OVD测试中',
    OVD_TESTED: 'OVD已测试',
    EVALUATING: '评估中',
    EVALUATED: '已评估',
    DATASET_SEARCHED: '已完成数据集检索',
    AWAITING_USER_JUDGMENT: '已提交用户判断',
    ESTIMATING: '估算中',
    COMPLETED: '已完成',
    FAILED: '失败'
  }
  return textMap[status] || status
}

const formatDate = (dateStr) => {
  if (!dateStr) return '-'
  const date = new Date(dateStr)
  return date.toLocaleString('zh-CN')
}

const getImageUrl = (path) => {
  if (!path) return ''
  if (path.startsWith('http')) return path
  // 如果是相对路径，添加/api/v1/files/前缀
  if (!path.startsWith('/')) {
    return `${import.meta.env.VITE_API_BASE_URL || ''}/api/v1/files/${path}`
  }
  return `${import.meta.env.VITE_API_BASE_URL || ''}${path}`
}

const parseTasksJson = (tasksStr) => {
  try {
    return JSON.parse(tasksStr)
  } catch {
    return []
  }
}

const handleSubmitUserJudgment = async () => {
  if (!judgmentForm.datasetMatchLevel) {
    ElMessage.warning('请选择数据集匹配度')
    return
  }

  submittingJudgment.value = true
  try {
    await feasibilityAPI.submitUserJudgment(assessmentId.value, {
      datasetMatchLevel: judgmentForm.datasetMatchLevel,
      userNotes: judgmentForm.userNotes
    })
    ElMessage.success('用户判断提交成功')
    await loadAssessment()
    await loadCategories()
  } catch (error) {
    console.error('提交用户判断失败:', error)
  } finally {
    submittingJudgment.value = false
  }
}

const loadAssessment = async () => {
  loading.value = true
  try {
    const res = await feasibilityAPI.getAssessment(assessmentId.value)
    assessment.value = res.data || {}
    judgmentForm.datasetMatchLevel = assessment.value.datasetMatchLevel || ''
    judgmentForm.userNotes = assessment.value.userJudgmentNotes || ''
  } catch (error) {
    console.error('加载评估失败:', error)
  } finally {
    loading.value = false
  }
}

const loadCategories = async () => {
  try {
    const res = await feasibilityAPI.getCategories(assessmentId.value)
    categories.value = res.data || []
  } catch (error) {
    console.error('加载类别失败:', error)
  }
}

const loadOvdResults = async () => {
  try {
    const res = await feasibilityAPI.getOvdResults(assessmentId.value)
    ovdResults.value = res.data || []
  } catch (error) {
    console.error('加载OVD结果失败:', error)
  }
}

const loadDatasets = async () => {
  try {
    const res = await feasibilityAPI.getDatasets(assessmentId.value)
    datasets.value = res.data || []
  } catch (error) {
    console.error('加载数据集失败:', error)
  }
}

const loadResourceEstimations = async () => {
  try {
    const res = await feasibilityAPI.getResourceEstimations(assessmentId.value)
    resourceEstimations.value = res.data || []
  } catch (error) {
    console.error('加载资源估算失败:', error)
  }
}

const loadImplementationPlans = async () => {
  try {
    const res = await feasibilityAPI.getImplementationPlans(assessmentId.value)
    implementationPlans.value = res.data || []
  } catch (error) {
    console.error('加载实施计划失败:', error)
  }
}

const loadVlmEvaluationDetails = async () => {
  try {
    const details = []
    for (const ovdResult of ovdResults.value) {
      const res = await feasibilityAPI.getVlmQualityScores(ovdResult.id)
      if (res.data && res.data.length > 0) {
        const qualityScore = res.data[0]
        if (qualityScore.evaluationDetails && qualityScore.evaluationDetails.length > 0) {
          details.push({
            categoryName: ovdResult.categoryName,
            imageName: ovdResult.imagePath.split('/').pop(),
            precision: qualityScore.precisionEstimate || 0,
            recall: qualityScore.recallEstimate || 0,
            details: qualityScore.evaluationDetails
          })
        }
      }
    }
    vlmEvaluationDetails.value = details
  } catch (error) {
    console.error('加载VLM评估详细结果失败:', error)
  }
}

const handleParse = async () => {
  parsing.value = true
  try {
    await feasibilityAPI.parseRequirement(assessmentId.value)
    ElMessage.success('解析成功')
    await loadAssessment()
    await loadCategories()
  } catch (error) {
    console.error('解析失败:', error)
  } finally {
    parsing.value = false
  }
}

const handleTestImageSuccess = (response) => {
  if (response.success && response.data) {
    testImages.value.push(response.data.url || response.data.path)
  }
}

const handleRunOvdTest = async () => {
  ovdTesting.value = true
  try {
    await feasibilityAPI.runOvdTest(assessmentId.value, {
      imagePaths: assessment.value.imageUrls
    })
    ElMessage.success('OVD测试完成')
    await loadAssessment()
    await loadOvdResults()
  } catch (error) {
    console.error('OVD测试失败:', error)
  } finally {
    ovdTesting.value = false
  }
}

const handleEvaluate = async () => {
  evaluating.value = true
  try {
    await feasibilityAPI.evaluate(assessmentId.value)
    ElMessage.success('评估完成')
    await loadAssessment()
    await loadCategories()
    await loadVlmEvaluationDetails()
    
    // 评估完成后，根据结果给出提示
    if (isAllBucketA.value) {
      ElMessage.success('所有类别均可使用OVD模型，无需定制训练')
    } else {
      ElMessage.warning('部分类别需要定制训练，请检索公开数据集')
    }
  } catch (error) {
    console.error('评估失败:', error)
  } finally {
    evaluating.value = false
  }
}

const handleSearchDatasets = async () => {
  searchingDatasets.value = true
  try {
    await feasibilityAPI.searchDatasets(assessmentId.value)
    ElMessage.success('数据集检索完成')
    await loadAssessment()
    await loadDatasets()
  } catch (error) {
    console.error('数据集检索失败:', error)
  } finally {
    searchingDatasets.value = false
  }
}

const openRoboflowSearch = () => {
  if (roboflowSearchUrl.value) {
    window.open(roboflowSearchUrl.value, '_blank', 'noopener,noreferrer')
  }
}

const handleEstimate = async () => {
  estimating.value = true
  try {
    await feasibilityAPI.estimate(assessmentId.value)
    ElMessage.success('估算完成')
    await loadAssessment()
    await loadDatasets()
    await loadResourceEstimations()
  } catch (error) {
    console.error('估算失败:', error)
  } finally {
    estimating.value = false
  }
}

const formatSampleCount = (count) => {
  if (!count || count === 0) return '0'
  if (count >= 1000) {
    return (count / 1000).toFixed(2).replace(/\.?0+$/, '') + 'k'
  }
  return count.toString()
}

const handleGeneratePlan = async () => {
  generatingPlan.value = true
  try {
    await feasibilityAPI.generatePlan(assessmentId.value)
    ElMessage.success('计划生成完成')
    await loadAssessment()
    await loadImplementationPlans()
  } catch (error) {
    console.error('生成计划失败:', error)
  } finally {
    generatingPlan.value = false
  }
}

const handleGenerateAIReport = async () => {
  generatingAIReport.value = true
  try {
    const res = await feasibilityAPI.generateAIReport(assessmentId.value)
    aiReport.value = res.data
    ElMessage.success('AI报告生成完成')
  } catch (error) {
    console.error('生成AI报告失败:', error)
    ElMessage.error('生成AI报告失败，请稍后重试')
  } finally {
    generatingAIReport.value = false
  }
}

const renderedReport = computed(() => {
  if (!aiReport.value || !aiReport.value.report) return ''
  // Simple markdown to HTML conversion (basic support)
  let html = aiReport.value.report
  // Headers
  html = html.replace(/^### (.*$)/gim, '<h3>$1</h3>')
  html = html.replace(/^## (.*$)/gim, '<h2>$1</h2>')
  html = html.replace(/^# (.*$)/gim, '<h1>$1</h1>')
  // Bold
  html = html.replace(/\*\*(.*?)\*\*/gim, '<strong>$1</strong>')
  // Lists
  html = html.replace(/^\- (.*$)/gim, '<li>$1</li>')
  html = html.replace(/(<li>.*<\/li>)/s, '<ul>$1</ul>')
  // Line breaks
  html = html.replace(/\n/gim, '<br>')
  return html
})

const handleEditCategory = (category) => {
  editingCategory.value = { ...category }
  editCategoryDialog.value = true
}

const handleSaveCategory = async () => {
  try {
    await feasibilityAPI.updateCategory(assessmentId.value, editingCategory.value.id, editingCategory.value)
    ElMessage.success('保存成功')
    editCategoryDialog.value = false
    await loadCategories()
  } catch (error) {
    console.error('保存失败:', error)
  }
}

const handleDeleteCategory = async (categoryId) => {
  try {
    await ElMessageBox.confirm('确定要删除此类别吗？', '提示', {
      confirmButtonText: '确定',
      cancelButtonText: '取消',
      type: 'warning'
    })

    await feasibilityAPI.deleteCategory(assessmentId.value, categoryId)
    ElMessage.success('删除成功')
    await loadCategories()
  } catch (error) {
    if (error !== 'cancel') {
      console.error('删除失败:', error)
    }
  }
}

const handleImageError = (event) => {
  console.error('图片加载失败:', event.target.src)
  event.target.style.display = 'none'
  event.target.parentElement.innerHTML = '<div class="no-image">图片加载失败</div>'
}

const handleExportReport = () => {
  const dataStr = JSON.stringify(report.value, null, 2)
  const blob = new Blob([dataStr], { type: 'application/json' })
  const url = URL.createObjectURL(blob)
  const link = document.createElement('a')
  link.href = url
  link.download = `assessment-${assessmentId.value}-report.json`
  link.click()
  URL.revokeObjectURL(url)
  ElMessage.success('导出成功')
}

onMounted(async () => {
  await loadAssessment()
  
  if (currentStep.value >= 1) {
    await loadCategories()
  }
  if (currentStep.value >= 2) {
    await loadOvdResults()
  }
  if (currentStep.value >= 3) {
    await loadVlmEvaluationDetails()
    await loadDatasets()
    await loadResourceEstimations()
  }
  if (currentStep.value >= 4) {
    await loadImplementationPlans()
  }
  
  if (assessment.value.status === 'COMPLETED') {
    try {
      const res = await feasibilityAPI.getReport(assessmentId.value)
      report.value = res.data
    } catch (error) {
      console.error('加载报告失败:', error)
    }
  }
})
</script>

<style scoped>
.assessment-detail {
  padding: 20px;
}

.header {
  margin-bottom: 30px;
}

.title-section {
  display: flex;
  align-items: center;
  gap: 15px;
  margin-bottom: 10px;
}

.title-section h2 {
  margin: 0;
  font-size: 24px;
  font-weight: 500;
}

.meta-info {
  color: #666;
  font-size: 14px;
}

.step-card {
  margin-bottom: 20px;
}

.card-header {
  font-size: 18px;
  font-weight: 500;
}

.step-content {
  padding: 10px 0;
}

.ovd-results-grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(300px, 1fr));
  gap: 20px;
  margin-top: 15px;
}

.result-card {
  height: 100%;
}

.result-content {
  display: flex;
  flex-direction: column;
  gap: 10px;
}

.result-image {
  width: 100%;
  height: 200px;
}

.result-image :deep(.el-image) {
  width: 100%;
  height: 100%;
}

.result-info p {
  margin: 5px 0;
  font-size: 14px;
}

.buckets-grid {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(300px, 1fr));
  gap: 20px;
  margin-top: 15px;
}

.bucket-card {
  height: 100%;
}

.bucket-a {
  border: 2px solid #67c23a;
}

.bucket-b {
  border: 2px solid #e6a23c;
}

.bucket-c {
  border: 2px solid #409eff;
}

.bucket-header {
  display: flex;
  align-items: center;
  gap: 8px;
  font-size: 16px;
  font-weight: 500;
}

.category-item {
  padding: 10px 0;
  border-bottom: 1px solid #eee;
}

.category-item:last-child {
  border-bottom: none;
}

.category-item h5 {
  margin: 0 0 8px 0;
}

.category-item p {
  margin: 5px 0;
  font-size: 14px;
  color: #666;
}

.link-text {
  color: #409eff;
  text-decoration: none;
}

.link-text:hover {
  text-decoration: underline;
}

.roboflow-action {
  margin-top: 14px;
}

.user-judgment-section {
  margin-top: 24px;
  padding: 16px;
  border: 1px solid #ebeef5;
  border-radius: 8px;
  background: #fafafa;
}

.report-content {
  max-height: 70vh;
  overflow-y: auto;
}

.report-content h3 {
  margin-top: 20px;
  margin-bottom: 15px;
}

:deep(.el-timeline-item__timestamp) {
  font-size: 14px;
  font-weight: 500;
  color: #409eff;
}

.vlm-details-grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(350px, 1fr));
  gap: 15px;
  margin-top: 15px;
}

.vlm-detail-card {
  border: 2px solid #e4e7ed;
  transition: all 0.3s;
}

.vlm-detail-card.correct {
  border-color: #67c23a;
  background-color: #f0f9ff;
}

.vlm-detail-card.incorrect {
  border-color: #f56c6c;
  background-color: #fef0f0;
}

.detail-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 12px;
  padding-bottom: 8px;
  border-bottom: 1px solid #eee;
}

.bbox-index {
  font-size: 13px;
  color: #666;
  font-weight: 500;
}

.detail-content {
  display: flex;
  gap: 15px;
}

.cropped-image-container {
  flex-shrink: 0;
  width: 120px;
  height: 120px;
  border: 1px solid #ddd;
  border-radius: 4px;
  overflow: hidden;
  display: flex;
  align-items: center;
  justify-content: center;
  background-color: #f5f5f5;
}

.cropped-image {
  max-width: 100%;
  max-height: 100%;
  object-fit: contain;
}

.no-image {
  color: #999;
  font-size: 12px;
  text-align: center;
}

.detail-info {
  flex: 1;
  display: flex;
  flex-direction: column;
  gap: 10px;
}

.info-item {
  font-size: 13px;
}

.info-item strong {
  color: #333;
  display: block;
  margin-bottom: 4px;
}

.question-text {
  margin: 0;
  padding: 8px;
  background-color: #f9f9f9;
  border-left: 3px solid #409eff;
  font-size: 12px;
  line-height: 1.5;
  color: #666;
  white-space: pre-wrap;
  word-break: break-word;
}

.error-reason {
  color: #f56c6c;
  background-color: #fef0f0;
  padding: 8px;
  border-radius: 4px;
  font-size: 12px;
}

.ai-report-section {
  margin-top: 30px;
}

.report-card {
  background: #fff;
  border: 1px solid #e4e7ed;
}

.markdown-body {
  padding: 20px;
  line-height: 1.8;
  color: #333;
}

.markdown-body h1 {
  font-size: 28px;
  font-weight: 600;
  margin: 24px 0 16px 0;
  padding-bottom: 12px;
  border-bottom: 2px solid #409eff;
  color: #303133;
}

.markdown-body h2 {
  font-size: 24px;
  font-weight: 600;
  margin: 20px 0 14px 0;
  padding-bottom: 8px;
  border-bottom: 1px solid #dcdfe6;
  color: #303133;
}

.markdown-body h3 {
  font-size: 20px;
  font-weight: 600;
  margin: 16px 0 12px 0;
  color: #606266;
}

.markdown-body h4 {
  font-size: 18px;
  font-weight: 600;
  margin: 14px 0 10px 0;
  color: #606266;
}

.markdown-body p {
  margin: 10px 0;
  line-height: 1.8;
}

.markdown-body ul {
  margin: 10px 0;
  padding-left: 30px;
  list-style-type: disc;
}

.markdown-body li {
  margin: 6px 0;
  line-height: 1.6;
}

.markdown-body strong {
  font-weight: 600;
  color: #303133;
}

.markdown-body code {
  background-color: #f5f7fa;
  padding: 2px 6px;
  border-radius: 3px;
  font-family: 'Courier New', monospace;
  font-size: 14px;
  color: #e83e8c;
}

.markdown-body blockquote {
  margin: 15px 0;
  padding: 10px 20px;
  background-color: #f9f9f9;
  border-left: 4px solid #409eff;
  color: #666;
}
</style>
