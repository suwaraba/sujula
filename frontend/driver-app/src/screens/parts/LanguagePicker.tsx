import { LANGUAGES, useLanguage } from '../../i18n';

/** Always reachable, never buried: the sign-in screen has it too. */
export function LanguagePicker() {
  const { language, setLanguage } = useLanguage();
  return (
    <div className="chips" role="group" aria-label="Language">
      {LANGUAGES.map((option) => (
        <button
          key={option.code}
          type="button"
          className="chip"
          aria-pressed={language === option.code}
          onClick={() => setLanguage(option.code)}
        >
          🌐 {option.name}
        </button>
      ))}
    </div>
  );
}
