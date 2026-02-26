import AVFoundation

final class TTSService {
    private let synthesizer = AVSpeechSynthesizer()

    func speak(_ text: String) {
        guard !text.isEmpty else { return }
        configureAudioSessionIfNeeded()

        let utterance = AVSpeechUtterance(string: text)
        utterance.rate = 0.5
        utterance.pitchMultiplier = 1.1
        utterance.voice = AVSpeechSynthesisVoice(language: "ru-RU")

        synthesizer.speak(utterance)
    }

    private func configureAudioSessionIfNeeded() {
        let session = AVAudioSession.sharedInstance()
        do {
            try session.setCategory(.playback, mode: .spokenAudio, options: [.duckOthers])
            try session.setActive(true, options: [])
        } catch {
            // No-op; TTS may still work.
        }
    }
}
