🦯 Vision Assist

AI-powered Smart Navigation System for Visually Impaired Individuals

Real-time Object Detection + Hardware-based Obstacle Avoidance

📌 Overview

Vision Assist is an integrated assistive navigation system designed to help visually impaired users detect obstacles and navigate safely in both indoor and outdoor environments.

The system combines:

✔ 1. Smartphone-based Object Detection

Using YOLOv8-Nano (COCO) + a custom-trained YOLOv8-Nano model for detecting:

Stairs 

Potholes

Doors

All 80 COCO classes (people, vehicles, obstacles, etc.)

✔ 2. Belt-Mounted Wearable Hardware

Components used:

Arduino UNO R3

HC-SR04 Ultrasonic sensor

Servo mount 

Bluetooth Module (HC-05)

2 x Li-ion Cells (18650) + Double Cell Holder

This module detects low-level obstacles that the smartphone camera may miss and alerts the user.

✔ 3. Sensor Fusion (Camera + Ultrasonic)

Combines both detection sources for reliability under:

Low light

Small / low obstacles

Partially occluded objects

Irregular surfaces

🚀 Features

📱 Real-time object detection (YOLOv8 Nano – TFLite)

🧠 Custom YOLOv8 model (stairs, potholes, doors)

🔊 Voice alerts (Text-to-Speech)

📳 Haptic alerts (future upgrade)

🛠️ Ultrasonic belt for depth sensing

🔁 FSM-based hardware alert logic

⚡ Low-latency on-device inference

🧭 Designed for real-world navigation

🧩 System Architecture
Smartphone Camera → YOLOv8 Nano → Object Detection → Voice Alerts
                        |
                        └──> Custom YOLO (Stairs / Potholes / Doors)

Wearable Belt → Ultrasonic Sensor → Distance Measurement → Audio Alerts (FSM)


Both components work independently but complement each other.

🧠 YOLOv8-Nano – Theory
📍 What is YOLOv8-Nano?

A mobile-optimized lightweight variant that delivers fast inference speeds on Android devices.

📌 Key Concepts

One-stage detector

Anchor-free

FPN (Feature Pyramid Network)

Decoupled head (classification + regression)

NMS for merging overlapping boxes

🧠 Custom YOLOv8-Nano (Stairs, Potholes, Doors)
📌 Dataset

Includes:

Stairs

Potholes

Doors

Annotated in YOLO format.

📌 Training (Google Colab)
pip install ultralytics
yolo detect train model=yolov8n.pt data=data.yaml epochs=100 imgsz=640
yolo export model=best.pt format=tflite


Outputs:

best.pt → Best weights

best.tflite → Mobile model

Integrated into the Android app for environment-specific detection.

🔧 Hardware (Wearable Belt Module)
Component	Purpose
Arduino UNO R3	Main controller
HC-SR04 Ultrasonic	Distance detection
Servo Mount	Optional scanning
Battery	Power
Working

Distance = (Time × Speed of Sound) / 2

FSM Logic:

Safe Zone → "Path Clear"

Warning Zone → "Caution,obstacle ahead"

Danger Zone → "Stop - Obstacle very close"



🛠️ Installation & Running Instructions
📱 Running the Android App
git clone https://github.com/Bhavishya-chawla08/VisionAssist.git


Open in Android Studio

Sync Gradle

Place TFLite models in:

app/src/main/assets/


Run on physical device (required for camera)

🔧 Running the Hardware

Install Arduino IDE

Open:

Hardware/Arduino_Code/main.ino


Select board: Arduino UNO

Upload

Power the module

Test buzzer response

📊 Expected Results

0.50–0.55 mAP on YOLOv8 Nano (COCO)

High accuracy for:

Stairs

Doors

Potholes

Ultrasonic module detects up to 4 meters reliably.

🎯 Conclusion

Vision Assist combines a mobile AI detection system with a wearable ultrasonic module to provide safe navigation support for visually impaired users. Its hybrid design ensures effective detection of both high-level and low-level obstacles.

🔮 Future Scope

GPS-based navigation

Gyroscope alignment

Improved haptic feedback

Face recognition

Smart glasses integration

ArduCam / LiDAR expansion
