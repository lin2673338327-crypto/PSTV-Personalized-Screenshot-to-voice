截图转语音系统 (Screenshot-to-Speech)

一个基于人工智能的桌面应用，能够将图片（如手机截图）中的文字信息提取出来，并合成为语音输出。项目包含一个用于处理核心 AI 服务的后端 API 服务器，以及一个图形化的桌面客户端。

✨ 功能特色

图片上传：支持通过图形界面选取多张图片上传。

智能识图：使用 OpenAI GPT-4o 模型，智能地从图片中提取关键文字内容。

文字转语音：整合 GPT-SoVITS 语音合成服务，将提取的文字转换为高质量的语音。

即时播放：直接在桌面客户端中播放生成的音频文件。

📁 项目结构
复制
Screenshot-to-Speech/
├── screenreader_server2.py     # 核心后端服务器 (Flask API)
├── main.py                     # 图形化桌面客户端 (KivyMD)
├── screenreader2.py            # 命令行测试客户端 (选用)
├── requirements.txt            # Python 依赖清单
├── README.md                   # 本文件
└── .gitignore                  # Git 忽略清单 (建议创建)
🚀 快速开始
环境需求

Python 版本: 3.8 或更高

操作系统: Windows, macOS, Linux

1. 安装依赖

克隆或下载本项目，在终端中进入项目根目录，执行以下命令安装所有 Python 依赖：

bash
复制
pip install -r requirements.txt

（requirements.txt包含了 Flask, OpenAI, Kivy, KivyMD 等所有必要包及其版本，请参考本文件末尾的完整清单。）

2. 配置后端服务器 (screenreader_server2.py)

后端服务器需要配置两个关键服务：

a) OpenAI API

前往 OpenAI Platform
申请 API 密钥。

打开 screenreader_server2.py文件。

找到以下代码段，将 "YOUR_OPENAI_API_KEY_HERE"替换为您的真实 API 密钥：

client = OpenAI(
    base_url="https://api.openai.com/v1",  # 若使用官方服务
    api_key="YOUR_OPENAI_API_KEY_HERE"    # <-- 在此替换
)

b) GPT-SoVITS 语音合成服务

您需要一个正在运行的 GPT-SoVITS 服务器。如果您没有，可以参考 GPT-SoVITS 官方项目
进行部署。

在 screenreader_server2.py中，找到 tts_infer_with_gpt_sovits函数内的参数。

修改变量以指向您的服务：

tts_url = "http://your-tts-server-address:port/tts"  # 替换为您的服务地址
data = {
    "text": text,
    "text_language": "zh",  # 可根据需要修改语言
    "ref_audio_path": "/path/to/your/reference_audio.wav",  # 参考音频路径
    "prompt_text": "您的提示文本，用于控制语音风格。",  # 提示文本
    # ... 其他参数
}

3. 启动后端服务器

在终端中执行以下命令启动 Flask 服务器：

bash
复制
python screenreader_server2.py

服务器启动后，您将看到类似以下的输出，表示服务已在 http://127.0.0.1:9980上运行：

复制
* Serving Flask app 'screenreader_server2'
 * Debug mode: on
 * Running on http://127.0.0.1:9980

4. 配置并启动桌面客户端 (main.py)

打开 main.py文件。

找到 upload_images函数（大约第 65 行），将 url变量修改为您的后端服务器地址：

python
下载
复制
# 如果后端在本地运行
url = "http://127.0.0.1:9980/synthesize"
# 如果后端在远端服务器，请使用其 IP 或域名
# url = "http://your-server-ip:9980/synthesize"

在另一个终端窗口中，执行以下命令启动图形化客户端：

bash
复制
python main.py

应用窗口将弹出。您可以使用 “Select Images” 按钮选择图片，然后点击 “Send to Server” 上传并生成语音，最后点击 “Play Audio” 播放。


🔧 进阶配置

修改后端连接端口：在 screenreader_server2.py文件末尾修改 app.run(host='0.0.0.0', port=9980)中的 port参数。

调整 AI 模型：在 screenreader_server2.py的 client.chat.completions.create调用中，可以修改 model参数（默认为 "gpt-4o"）以使用其他支持视觉的模型。

音频输出格式：后端目前输出 WAV 格式。您可以在 screenreader_server2.py中修改相关代码以支持 MP3 等其他格式。


⚠️ 注意事项

API 费用：使用 OpenAI API 会产生费用，请留意您的用量。

网络连接：后端服务器需要能稳定访问互联网，以调用 OpenAI 和 GPT-SoVITS 服务。

语音合成服务：本项目依赖于外部的 GPT-SoVITS 服务，请确保该服务已正确部署并可以访问。

敏感信息：切勿将包含真实 API 密钥的代码上传到公开仓库。建议使用环境变量或配置文件管理密钥。

📄 附录：完整依赖清单 (requirements.txt)

项目依赖的完整清单如下，您也可以直接查看项目根目录下的 requirements.txt文件。

复制
# 后端核心
Flask==3.1.3
openai==2.24.0
requests==2.32.5
pydantic==2.12.5
Pillow==11.3.0

# 桌面客户端
Kivy==2.3.1
kivymd==1.2.0
plyer==2.1.0

# 通用工具与依赖
annotated-types==0.7.0
anyio==4.12.1
blinker==1.9.0
certifi==2026.2.25
charset-normalizer==3.4.4
click==8.1.8
# ... 其他依赖请参见项目内的 requirements.txt 文件