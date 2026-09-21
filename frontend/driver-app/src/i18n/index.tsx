/**
 * Words, in the driver's own language.
 *
 * Deliberately tiny — a map and a hook, no library. The app is built so that
 * the words are the smallest part of it: every action is an icon with a colour
 * and a fixed position, the flow is one decision per screen, and a driver who
 * reads nothing can still work the whole round by the pictures. The strings are
 * here for everyone else.
 *
 * Two honest limits, both worth knowing rather than discovering:
 *
 *  - the backend writes its own sentences — "That code is not right. Ask them
 *    to read it out again" — and they arrive in English. They are shown as
 *    written rather than guessed at, because a mistranslated instruction about
 *    a parcel is worse than a clear one in the wrong language. Translating them
 *    is a backend change, not a frontend one.
 *  - `en` and `fr` cover the two sides of this corridor. Adding Wolof or Mandinka
 *    is adding one object below; nothing else in the app changes.
 */

import { createContext, useCallback, useContext, useMemo, useState, type ReactNode } from 'react';

const en = {
  'app.name': 'Sujula Driver',
  'app.offline': 'No connection. Your work is saved on this phone.',
  'app.pending': '{count} waiting to send',
  'app.retry': 'Try again',
  'app.close': 'Close',
  'app.back': 'Back',
  'app.cancel': 'Cancel',
  'app.saving': 'Saving…',
  'app.loading': 'One moment…',

  'nav.jobs': 'Jobs',
  'nav.earnings': 'Money',
  'nav.history': 'Done',
  'nav.profile': 'Me',

  'login.title': 'Sign in',
  'login.email': 'Email',
  'login.password': 'Password',
  'login.submit': 'Sign in',
  'login.mfa': 'Enter the six digits from your authenticator app',
  'login.forgot': 'I forgot my password',
  'login.notDriver':
    'This account does not carry parcels yet. Apply below and dispatch will review it.',

  'pin.create': 'Choose a 4-digit PIN for this phone',
  'pin.confirm': 'Enter it again',
  'pin.mismatch': 'Those two do not match. Try again.',
  'pin.unlock': 'Enter your PIN',
  'pin.wrong': 'Wrong PIN. {left} tries left.',
  'pin.locked': 'Too many wrong tries. Sign in again.',
  'pin.why': 'This phone is shared. The PIN keeps your round yours.',

  'apply.title': 'Apply to carry parcels',
  'apply.submit': 'Send application',
  'apply.pending': 'Dispatch is checking your papers.',
  'apply.rejected': 'Your application was not accepted.',
  'apply.suspended': 'Your account is paused. Call dispatch.',

  'home.online': 'Working',
  'home.offline': 'Not working',
  'home.goOnline': 'Start work',
  'home.goOffline': 'Stop work',
  'home.offers': 'New jobs',
  'home.mine': 'My jobs',
  'home.noOffers': 'No new jobs right now.',
  'home.noJobs': 'Nothing to carry yet.',
  'home.mustBeOnline': 'Start work to get jobs.',

  'offer.accept': 'Take it',
  'offer.decline': 'No',
  'offer.expires': '{seconds}s to decide',
  'offer.parcels': '{count} parcel(s)',
  'decline.why': 'Why not?',
  'decline.tooFar': 'Too far',
  'decline.busy': 'Already full',
  'decline.payment': 'Pays too little',
  'decline.vehicle': 'Wrong vehicle',
  'decline.finished': 'Finished for today',

  'job.toShop': 'Go to the shop',
  'job.arrived': 'I am at the shop',
  'job.collect': 'Collect the parcel',
  'job.deliver': 'Hand it over',
  'job.deposit': 'Leave at the counter',
  'job.failed': 'It did not work',
  'job.transfer': 'Give to another driver',
  'job.navigate': 'Directions',
  'job.call': 'Call',
  'job.whatsapp': 'WhatsApp',
  'job.chain': 'What has happened',
  'job.privacy': 'The address appears when the parcel is in your hands.',

  'code.ask': 'Ask for the code',
  'code.sellerCode': "Ask the seller for their code",
  'code.recipientCode': 'Ask the person for their code',
  'code.sent': 'The code has been sent to the buyer.',
  'code.neverShown': 'You never see the code. They read it to you.',
  'code.enter': 'Type the 6 digits',
  'code.scan': 'Scan the label',

  'photo.take': 'Take the photo',
  'photo.retake': 'Take it again',
  'photo.required': 'A photo is needed to finish a delivery.',

  'position.waiting': 'Finding where you are…',
  'position.denied': 'This app needs to know where you are. Turn location on.',
  'position.stale': 'Your position is old. Wait a moment.',
  'position.vague': 'Your position is not exact. Move outside if you can.',
  'position.far': 'You are {metres}m from where this parcel should go.',

  'earnings.title': 'What you have earned',
  'earnings.none': 'Nothing yet for these days.',
  'earnings.deliveries': '{count} delivered',
  'history.title': 'Jobs you have finished',
  'history.none': 'Nothing finished yet.',

  'profile.title': 'Me',
  'profile.vehicle': 'What I drive',
  'profile.zone': 'Where I work',
  'profile.signOut': 'Sign out',
  'profile.signOutWarning':
    '{count} of your records have not been sent. Find signal first, or they are lost.',
  'profile.score': 'You take {percent}% of the jobs offered to you.',

  'outbox.title': 'Waiting to send',
  'outbox.empty': 'Everything is sent.',
  'outbox.rejected': 'This one was refused: {problem}',
  'outbox.sendNow': 'Send now',
  'outbox.discard': 'Remove',
} as const;

export type StringKey = keyof typeof en;

const fr: Record<StringKey, string> = {
  'app.name': 'Sujula Chauffeur',
  'app.offline': 'Pas de réseau. Votre travail est gardé sur ce téléphone.',
  'app.pending': '{count} en attente d’envoi',
  'app.retry': 'Réessayer',
  'app.close': 'Fermer',
  'app.back': 'Retour',
  'app.cancel': 'Annuler',
  'app.saving': 'Enregistrement…',
  'app.loading': 'Un instant…',

  'nav.jobs': 'Courses',
  'nav.earnings': 'Argent',
  'nav.history': 'Terminé',
  'nav.profile': 'Moi',

  'login.title': 'Se connecter',
  'login.email': 'E-mail',
  'login.password': 'Mot de passe',
  'login.submit': 'Se connecter',
  'login.mfa': 'Entrez les six chiffres de votre application',
  'login.forgot': 'J’ai oublié mon mot de passe',
  'login.notDriver':
    'Ce compte ne transporte pas encore de colis. Postulez ci-dessous.',

  'pin.create': 'Choisissez un code à 4 chiffres pour ce téléphone',
  'pin.confirm': 'Entrez-le encore',
  'pin.mismatch': 'Les deux ne sont pas pareils. Recommencez.',
  'pin.unlock': 'Entrez votre code',
  'pin.wrong': 'Code faux. Il reste {left} essais.',
  'pin.locked': 'Trop d’essais. Reconnectez-vous.',
  'pin.why': 'Ce téléphone est partagé. Le code protège votre tournée.',

  'apply.title': 'Postuler pour livrer',
  'apply.submit': 'Envoyer',
  'apply.pending': 'Vos papiers sont en cours de vérification.',
  'apply.rejected': 'Votre demande n’a pas été acceptée.',
  'apply.suspended': 'Votre compte est suspendu. Appelez le bureau.',

  'home.online': 'En service',
  'home.offline': 'Hors service',
  'home.goOnline': 'Commencer',
  'home.goOffline': 'Arrêter',
  'home.offers': 'Nouvelles courses',
  'home.mine': 'Mes courses',
  'home.noOffers': 'Pas de nouvelle course.',
  'home.noJobs': 'Rien à transporter.',
  'home.mustBeOnline': 'Commencez pour recevoir des courses.',

  'offer.accept': 'Je prends',
  'offer.decline': 'Non',
  'offer.expires': '{seconds}s pour décider',
  'offer.parcels': '{count} colis',
  'decline.why': 'Pourquoi ?',
  'decline.tooFar': 'Trop loin',
  'decline.busy': 'Déjà plein',
  'decline.payment': 'Paie trop peu',
  'decline.vehicle': 'Mauvais véhicule',
  'decline.finished': 'Fini pour aujourd’hui',

  'job.toShop': 'Aller à la boutique',
  'job.arrived': 'Je suis à la boutique',
  'job.collect': 'Prendre le colis',
  'job.deliver': 'Remettre le colis',
  'job.deposit': 'Laisser au comptoir',
  'job.failed': 'Ça n’a pas marché',
  'job.transfer': 'Donner à un autre chauffeur',
  'job.navigate': 'Itinéraire',
  'job.call': 'Appeler',
  'job.whatsapp': 'WhatsApp',
  'job.chain': 'Ce qui s’est passé',
  'job.privacy': 'L’adresse apparaît quand le colis est dans vos mains.',

  'code.ask': 'Demander le code',
  'code.sellerCode': 'Demandez son code au vendeur',
  'code.recipientCode': 'Demandez son code à la personne',
  'code.sent': 'Le code a été envoyé à l’acheteur.',
  'code.neverShown': 'Vous ne voyez jamais le code. On vous le lit.',
  'code.enter': 'Tapez les 6 chiffres',
  'code.scan': 'Scanner l’étiquette',

  'photo.take': 'Prendre la photo',
  'photo.retake': 'Reprendre',
  'photo.required': 'Une photo est obligatoire pour finir une livraison.',

  'position.waiting': 'Recherche de votre position…',
  'position.denied': 'L’application a besoin de votre position. Activez-la.',
  'position.stale': 'Votre position est ancienne. Attendez un peu.',
  'position.vague': 'Votre position n’est pas précise. Sortez si possible.',
  'position.far': 'Vous êtes à {metres} m du lieu de livraison.',

  'earnings.title': 'Ce que vous avez gagné',
  'earnings.none': 'Rien pour ces jours.',
  'earnings.deliveries': '{count} livrés',
  'history.title': 'Courses terminées',
  'history.none': 'Rien de terminé.',

  'profile.title': 'Moi',
  'profile.vehicle': 'Mon véhicule',
  'profile.zone': 'Ma zone',
  'profile.signOut': 'Se déconnecter',
  'profile.signOutWarning':
    '{count} de vos enregistrements ne sont pas envoyés. Trouvez du réseau d’abord.',
  'profile.score': 'Vous acceptez {percent}% des courses proposées.',

  'outbox.title': 'En attente d’envoi',
  'outbox.empty': 'Tout est envoyé.',
  'outbox.rejected': 'Refusé : {problem}',
  'outbox.sendNow': 'Envoyer maintenant',
  'outbox.discard': 'Retirer',
};

const DICTIONARIES = { en, fr } as const;
export type Language = keyof typeof DICTIONARIES;

export const LANGUAGES: Array<{ code: Language; name: string }> = [
  { code: 'en', name: 'English' },
  { code: 'fr', name: 'Français' },
];

const STORAGE_KEY = 'sujula.driver.lang';

function initial(): Language {
  try {
    const saved = localStorage.getItem(STORAGE_KEY);
    if (saved === 'en' || saved === 'fr') return saved;
  } catch {
    /* A browser with storage blocked still gets a working app. */
  }
  return navigator.language?.toLowerCase().startsWith('fr') ? 'fr' : 'en';
}

type Translate = (key: StringKey, values?: Record<string, string | number>) => string;

const I18nContext = createContext<{
  language: Language;
  setLanguage: (language: Language) => void;
  t: Translate;
} | null>(null);

export function I18nProvider({ children }: { children: ReactNode }) {
  const [language, setLanguageState] = useState<Language>(initial);

  const setLanguage = useCallback((next: Language) => {
    setLanguageState(next);
    document.documentElement.lang = next;
    try {
      localStorage.setItem(STORAGE_KEY, next);
    } catch {
      /* Not worth failing over. */
    }
  }, []);

  const t = useCallback<Translate>(
    (key, values) => {
      const template = DICTIONARIES[language][key] ?? en[key] ?? key;
      if (!values) return template;
      return template.replace(/\{(\w+)\}/g, (whole, name: string) =>
        name in values ? String(values[name]) : whole,
      );
    },
    [language],
  );

  const value = useMemo(() => ({ language, setLanguage, t }), [language, setLanguage, t]);
  return <I18nContext.Provider value={value}>{children}</I18nContext.Provider>;
}

export function useT(): Translate {
  const value = useContext(I18nContext);
  if (!value) throw new Error('useT must be used inside an I18nProvider');
  return value.t;
}

export function useLanguage() {
  const value = useContext(I18nContext);
  if (!value) throw new Error('useLanguage must be used inside an I18nProvider');
  return { language: value.language, setLanguage: value.setLanguage };
}
